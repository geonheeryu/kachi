package me.rgunny.kachi.ai.application.service.story

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.port.inbound.story.SummarizeStoryUseCase
import me.rgunny.kachi.ai.application.port.inbound.story.model.CreatedStorySummaryResult
import me.rgunny.kachi.ai.application.port.inbound.story.model.FailedStorySummaryResult
import me.rgunny.kachi.ai.application.port.inbound.story.model.SkippedStorySummaryResult
import me.rgunny.kachi.ai.application.port.inbound.story.model.StorySummarySkipReason
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeStoryCommand
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeStoryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxEventSerializer
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.AiOutboxEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StoryQuarantinedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySplitRequestedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySummaryCreatedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.toOutbox
import me.rgunny.kachi.ai.application.port.outbound.quarantine.StoryQuarantinePersistencePort
import me.rgunny.kachi.ai.application.port.outbound.story.AiStoryArticlePersistencePort
import me.rgunny.kachi.ai.application.port.outbound.story.AiStoryPersistencePort
import me.rgunny.kachi.ai.application.port.outbound.summary.StorySummaryPersistencePort
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.outbox.AiOutbox
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantine
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.story.AiStory
import me.rgunny.kachi.ai.domain.story.AiStoryArticle
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.domain.summary.StorySummary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * story 하나의 미요약 기사를 직전 버전 요약과 함께 LLM에 넣어 다음 버전을 만드는 유스케이스.
 *
 * 멱등은 (storyId, version) unique와 story 상태 CAS가 맡는다.
 * 저장 경합에서 지면 이번 LLM 결과를 버리고 SUPERSEDED로 끝낸다(같은 버전을 먼저 저장한 실행 있음).
 * story 탓으로 판정된 실패는 격리 카운트를 올리고 결과로 돌려주며, 그 밖의 실패는 예외로 올라가 재전달된다.
 */
@Service
class SummarizeStoryService(
    private val aiStoryPersistencePort: AiStoryPersistencePort,
    private val aiStoryArticlePersistencePort: AiStoryArticlePersistencePort,
    private val storySummaryPersistencePort: StorySummaryPersistencePort,
    private val storyQuarantinePersistencePort: StoryQuarantinePersistencePort,
    private val llmProviderPort: LlmProviderPort,
    private val eventSerializer: AiOutboxEventSerializer,
    private val policy: StorySummaryPolicy,
    private val quarantinePolicy: StoryQuarantinePolicy,
    private val clock: Clock
) : SummarizeStoryUseCase {

    override suspend fun summarize(command: SummarizeStoryCommand): SummarizeStoryResult {
        val now = Instant.now(clock)

        // 1. 격리된 story는 운영자가 해제할 때까지 요약하지 않는다.
        val quarantine = storyQuarantinePersistencePort.findByStoryId(command.storyId)
        if (quarantine?.isQuarantined == true) {
            return SkippedStorySummaryResult(StorySummarySkipReason.QUARANTINED)
        }

        // 2. 요약할 미요약 기사가 있는 story인지 확인한다.
        val story = aiStoryPersistencePort.findByStoryId(command.storyId)
        if (story == null || story.merged || story.pendingCount < 1) {
            return SkippedStorySummaryResult(StorySummarySkipReason.NO_PENDING)
        }
        val pending = aiStoryArticlePersistencePort.findPendingByStory(story.storyId, policy.maxArticlesPerVersion + 1)
        if (pending.isEmpty()) {
            return SkippedStorySummaryResult(StorySummarySkipReason.NO_PENDING)
        }
        val target = pending.take(policy.maxArticlesPerVersion)
        val remainingOldestPendingAt = pending.getOrNull(policy.maxArticlesPerVersion)?.attachedAt

        // 3. 직전 버전 요약과 새 기사로 LLM을 부른다. 실패의 책임 판정은 여기서 가른다.
        val previous = storySummaryPersistencePort.findLatest(story.storyId)
        val llmResult = try {
            callLlm(story, previous, target)
        } catch (error: CancellationException) {
            // coroutine 취소 전파(요약 실패 아님)
            throw error
        } catch (error: Exception) {
            return recordFailedStory(story, quarantine, error, now)
        }

        // 4. 버전·마킹·상태 CAS·outbox를 한 트랜잭션으로 저장한다. 경합에서 지면 이번 결과를 버린다.
        val summary = buildSummary(story, previous, target, llmResult)
        val outboxes = outboxesFor(summary)
        val saved = storySummaryPersistencePort.saveVersion(
            summary = summary,
            story = story.summarized(
                summarizedCount = target.size,
                remainingOldestPendingAt = remainingOldestPendingAt,
                now = summary.createdAt
            ),
            expectedStoryVersion = story.version,
            outboxes = outboxes
        )
        if (!saved) {
            log.info(
                "Story summary superseded by a concurrent execution: storyId={}, version={}",
                story.storyId.value,
                summary.version
            )
            return SkippedStorySummaryResult(StorySummarySkipReason.SUPERSEDED)
        }

        resetStoryFailures(quarantine, summary.createdAt)
        log.info(
            "Story summary created: storyId={}, version={}, developmentKind={}, newArticles={}, published={}",
            story.storyId.value,
            summary.version,
            summary.developmentKind,
            target.size,
            outboxes.isNotEmpty()
        )

        return CreatedStorySummaryResult(
            summaryId = summary.id,
            storyId = summary.storyId,
            version = summary.version,
            developmentKind = summary.developmentKind,
            newArticleCount = target.size,
            published = outboxes.isNotEmpty()
        )
    }

    private suspend fun callLlm(
        story: AiStory,
        previous: StorySummary?,
        target: List<AiStoryArticle>
    ): LlmStorySummaryResult {
        return llmProviderPort.prepareStorySummary().summarize(
            keywords = story.keywords,
            previousSummary = previous?.let { PreviousStorySummary(title = it.title, content = it.content) },
            articles = target.map { article ->
                StorySummaryArticle(
                    source = article.source,
                    title = article.title,
                    excerpt = article.excerpt,
                    publishedAt = article.publishedAt
                )
            }
        )
    }

    private fun buildSummary(
        story: AiStory,
        previous: StorySummary?,
        target: List<AiStoryArticle>,
        llmResult: LlmStorySummaryResult
    ): StorySummary {
        return StorySummary.create(
            storyId = story.storyId,
            version = story.nextVersion,
            keywords = story.keywords,
            newNewsIds = target.map { it.newsId },
            sourceNewsCount = (previous?.sourceNewsCount ?: 0) + target.size,
            title = llmResult.title,
            content = llmResult.content,
            sentiment = llmResult.sentiment,
            // 첫 버전의 전개 종류 고정(비교 대상 없음, LLM 판정 무시)
            developmentKind = if (previous == null) StoryDevelopmentKind.DEVELOPMENT else llmResult.developmentKind,
            provider = llmResult.metadata.provider,
            model = llmResult.metadata.model,
            requestedModel = llmResult.metadata.requestedModel,
            promptVersion = llmResult.metadata.promptVersion,
            tokenUsage = llmResult.metadata.tokenUsage,
            createdAt = Instant.now(clock)
        )
    }

    /**
     * 전개 종류가 정한 발행 대기 이벤트.
     *
     * NO_CHANGE는 빈 목록이다.
     */
    private fun outboxesFor(summary: StorySummary): List<AiOutbox> {
        return when (summary.developmentKind) {
            StoryDevelopmentKind.DEVELOPMENT -> listOf(toOutbox(StorySummaryCreatedEvent.from(summary), summary.createdAt))
            StoryDevelopmentKind.NEW_STORY -> listOf(toOutbox(StorySplitRequestedEvent.from(summary), summary.createdAt))
            StoryDevelopmentKind.NO_CHANGE -> emptyList()
        }
    }

    private fun toOutbox(event: AiOutboxEvent, now: Instant): AiOutbox {
        return event.toOutbox(payload = eventSerializer.serialize(event), now = now)
    }

    /**
     * story 탓 실패면 격리 카운트를 올리고 결과로 끝낸다.
     *
     * story 탓이 아닌 실패는 받은 예외를 그대로 던진다.
     */
    private suspend fun recordFailedStory(
        story: AiStory,
        quarantine: StoryQuarantine?,
        error: Exception,
        now: Instant
    ): SummarizeStoryResult {
        if (!storyBound(error)) {
            throw error
        }

        val reason = failureReasonOf(error)
        val tracked = quarantine ?: StoryQuarantine.track(storyId = story.storyId, updatedAt = now)
        val updated = tracked.recordFailure(
            reason = reason,
            failureThreshold = quarantinePolicy.failureThreshold,
            updatedAt = now
        )

        // 격리 전이 시점의 알림 이벤트
        if (updated.isQuarantined && !tracked.isQuarantined) {
            storyQuarantinePersistencePort.saveQuarantined(
                quarantine = updated,
                outbox = toOutbox(StoryQuarantinedEvent.from(updated), now)
            )
            log.error(
                "Story quarantined after consecutive summary failures: storyId={}, consecutiveFailures={}, lastFailureReason={}",
                story.storyId.value,
                updated.consecutiveFailures,
                reason
            )
        } else {
            storyQuarantinePersistencePort.save(updated)
            log.warn(
                "Story summary failed by story-bound error: storyId={}, reason={}, consecutiveFailures={}",
                story.storyId.value,
                reason,
                updated.consecutiveFailures,
                error
            )
        }

        return FailedStorySummaryResult(reason = reason, quarantined = updated.isQuarantined)
    }

    private suspend fun resetStoryFailures(quarantine: StoryQuarantine?, now: Instant) {
        // 초기화할 실패 누적 없음
        if (quarantine == null || !quarantine.needsReset()) {
            return
        }

        storyQuarantinePersistencePort.save(quarantine.recordSuccess(now))
    }

    private companion object {
        val log = LoggerFactory.getLogger(SummarizeStoryService::class.java)

        /**
         * story에 책임을 물을 수 있는 실패인지 판단한다.
         *
         * LLM 실패는 시도한 후보 전부가 입력 탓으로 끝났을 때만 story 탓이다.
         * 판별할 수 없는 실패는 false다.
         */
        fun storyBound(error: Throwable): Boolean {
            return when (error) {
                is LlmProviderException -> error.allInput
                is IllegalArgumentException -> true
                else -> false
            }
        }

        fun failureReasonOf(error: Throwable): AiFailureReason {
            return when (error) {
                is LlmProviderException -> failureReasonOf(error.failure.code)
                // 도메인 불변식 위반(story 입력·응답 탓)
                else -> AiFailureReason.INVALID_RESPONSE
            }
        }

        /** LLM 실패 코드에 대응하는 실행 기록의 실패 원인(when 완전 열거, else 없음). */
        fun failureReasonOf(code: LlmFailureCode): AiFailureReason {
            return when (code) {
                LlmFailureCode.LLM_TIMEOUT -> AiFailureReason.TIMEOUT
                LlmFailureCode.LLM_RATE_LIMITED -> AiFailureReason.RATE_LIMITED
                LlmFailureCode.LLM_REQUEST_REJECTED -> AiFailureReason.CLIENT_ERROR
                LlmFailureCode.LLM_SERVER_ERROR -> AiFailureReason.SERVER_ERROR
                LlmFailureCode.LLM_NETWORK_ERROR -> AiFailureReason.NETWORK_ERROR
                LlmFailureCode.LLM_INVALID_RESPONSE -> AiFailureReason.INVALID_RESPONSE
                LlmFailureCode.LLM_NOT_PERMITTED -> AiFailureReason.PROVIDER_UNAVAILABLE
                LlmFailureCode.LLM_MODEL_NOT_FOUND -> AiFailureReason.MODEL_NOT_FOUND

                LlmFailureCode.LLM_UNAUTHORIZED,
                LlmFailureCode.LLM_PAYMENT_REQUIRED,
                LlmFailureCode.LLM_FORBIDDEN -> AiFailureReason.ACCOUNT_ERROR

                LlmFailureCode.LLM_UNKNOWN_ERROR -> AiFailureReason.UNKNOWN
            }
        }
    }
}
