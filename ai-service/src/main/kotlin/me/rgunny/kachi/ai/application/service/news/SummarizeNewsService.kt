package me.rgunny.kachi.ai.application.service.news

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import me.rgunny.kachi.ai.application.exception.KeywordReaderException
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.exception.NewsReaderException
import me.rgunny.kachi.ai.application.port.inbound.news.SummarizeNewsUseCase
import me.rgunny.kachi.ai.application.port.inbound.news.model.ExplicitSummaryWindowRequest
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizeNewsCommand
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizeNewsResult
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizedNewsResult
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummaryWindowRequest
import me.rgunny.kachi.ai.application.port.inbound.news.model.WatermarkSummaryWindowRequest
import me.rgunny.kachi.ai.application.port.outbound.keyword.KeywordReaderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmGenerationMetadata
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmNewsSummary
import me.rgunny.kachi.ai.application.port.outbound.news.NewsReaderPort
import me.rgunny.kachi.ai.application.port.outbound.news.model.NewsArticle
import me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxEventSerializer
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.KeywordQuarantinedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.SummaryCreatedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.toOutbox
import me.rgunny.kachi.ai.application.port.outbound.quarantine.KeywordQuarantinePersistencePort
import me.rgunny.kachi.ai.application.port.outbound.run.AiRunPersistencePort
import me.rgunny.kachi.ai.application.port.outbound.summary.NewsSummaryPersistencePort
import me.rgunny.kachi.ai.application.port.outbound.watermark.SummaryWatermarkPersistencePort
import me.rgunny.kachi.ai.application.service.story.StoryQuarantinePolicy
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmFailure
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.TokenUsage
import me.rgunny.kachi.ai.domain.quarantine.KeywordQuarantine
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.run.AiRun
import me.rgunny.kachi.ai.domain.run.AiRunTargetType
import me.rgunny.kachi.ai.domain.run.AiSkipReason
import me.rgunny.kachi.ai.domain.summary.NewsHash
import me.rgunny.kachi.ai.domain.summary.NewsSummary
import me.rgunny.kachi.ai.domain.watermark.SummaryWatermark
import me.rgunny.kachi.ai.domain.watermark.SummaryWindow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SummarizeNewsService(
    private val keywordReaderPort: KeywordReaderPort,
    private val newsReaderPort: NewsReaderPort,
    private val llmProviderPort: LlmProviderPort,
    private val newsSummaryPersistencePort: NewsSummaryPersistencePort,
    private val aiRunPersistencePort: AiRunPersistencePort,
    private val summaryWatermarkPersistencePort: SummaryWatermarkPersistencePort,
    private val keywordQuarantinePersistencePort: KeywordQuarantinePersistencePort,
    private val eventSerializer: AiOutboxEventSerializer,
    private val quarantinePolicy: StoryQuarantinePolicy,
    private val clock: Clock
) : SummarizeNewsUseCase {

    override suspend fun summarize(command: SummarizeNewsCommand): SummarizeNewsResult {
        val now = Instant.now(clock)

        // 1. 격리 기록을 한 번만 읽어 대상 제외와 실패 누적에 함께 쓴다.
        val quarantines = loadQuarantines()
        val keywords = excludeQuarantined(resolveKeywords(command), quarantines)

        // 2. 이번 실행이 처리할 구간을 확정한다. watermark 기반일 때만 전진 대상이 생긴다.
        val watermark = loadWatermark(command.window)
        val window = resolveWindow(command.window, watermark?.position, now)

        // 3. 실행 기록을 RUNNING 상태로 먼저 남기고 키워드별로 요약한다.
        val startedRun = aiRunPersistencePort.save(
            AiRun.start(
                targetType = TARGET_TYPE,
                requestedKeywords = keywords.size,
                startedAt = now,
                windowFrom = window.from,
                windowTo = window.to
            )
        )
        val outcome = NewsSummaryOutcome.of(
            summarizeKeywords(keywords, window, command.maxArticlesPerKeyword, quarantines, now)
        )

        // 4. 격리 제외 키워드가 모두 성공했을 때만 watermark를 전진시킨다.
        // 실패가 있으면 그대로 두어 다음 실행이 같은 구간을 다시 처리하고, 성공분은 newsHash로 재사용한다.
        val watermarkAdvanced = advanceWatermarkIfCompleted(
            request = command.window,
            watermark = watermark,
            window = window,
            failureCount = outcome.failureCount,
            now = now
        )

        return SummarizeNewsResult.from(
            completeRun(startedRun, outcome, watermarkAdvanced),
            summaries = outcome.summaries
        )
    }

    /**
     * 요청 키워드가 없으면 외부 키워드 소유 서비스에서 활성 키워드를 읽는다.
     */
    private suspend fun resolveKeywords(command: SummarizeNewsCommand): List<AiKeyword> {
        return command.keywords.ifEmpty {
            keywordReaderPort.findActiveKeywords()
        }
    }

    private suspend fun loadQuarantines(): Map<AiKeyword, KeywordQuarantine> {
        return keywordQuarantinePersistencePort
            .findAllBy(TARGET_TYPE)
            .associateBy { it.keyword }
    }

    private fun excludeQuarantined(
        keywords: List<AiKeyword>,
        quarantines: Map<AiKeyword, KeywordQuarantine>
    ): List<AiKeyword> {
        val targets = keywords.filterNot { quarantines[it]?.isQuarantined == true }

        if (targets.size < keywords.size) {
            log.info(
                "Excluded quarantined keywords from news summary: requested={}, excluded={}",
                keywords.size,
                keywords.size - targets.size
            )
        }

        return targets
    }

    private suspend fun loadWatermark(request: SummaryWindowRequest): SummaryWatermark? {
        return when (request) {
            is WatermarkSummaryWindowRequest -> summaryWatermarkPersistencePort.findBy(TARGET_TYPE)
            is ExplicitSummaryWindowRequest -> null
        }
    }

    /**
     * 키워드를 순서대로 요약한다.
     *
     * 호출할 수 있는 모델이 하나도 없으면 남은 키워드는 호출하지 않고 건너뛴다.
     * 그 상태에서 같은 호출을 반복해도 결과는 같다.
     * watermark는 실패 때문에 유지되므로, 건너뛴 키워드는 다음 실행이 그대로 다시 처리한다.
     */
    private suspend fun summarizeKeywords(
        keywords: List<AiKeyword>,
        window: ResolvedSummaryWindow,
        maxArticlesPerKeyword: Int,
        quarantines: Map<AiKeyword, KeywordQuarantine>,
        now: Instant
    ): List<KeywordOutcome> {
        val outcomes = mutableListOf<KeywordOutcome>()
        var aborted = false

        for (keyword in keywords) {
            if (aborted) {
                outcomes += SkippedKeywordOutcome(AiSkipReason.PROVIDER_UNAVAILABLE)
                continue
            }

            val outcome = summarizeKeyword(keyword, window, maxArticlesPerKeyword, quarantines[keyword], now)
            outcomes += outcome

            if (outcome is FailedKeywordOutcome && outcome.abortsRun) {
                log.warn(
                    "Aborting remaining keywords in this run because no LLM model can be called: keyword={}, reason={}, remaining={}",
                    keyword.value,
                    outcome.reason,
                    keywords.size - outcomes.size
                )
                aborted = true
            }
        }

        return outcomes
    }

    /**
     * 키워드 하나를 요약하고 결과에 따라 연속 실패 누적을 갱신한다.
     *
     * 키워드 하나의 실패가 나머지 키워드를 막지 않도록 예외를 여기서 가둔다.
     */
    private suspend fun summarizeKeyword(
        keyword: AiKeyword,
        window: ResolvedSummaryWindow,
        maxArticlesPerKeyword: Int,
        quarantine: KeywordQuarantine?,
        now: Instant
    ): KeywordOutcome {
        return try {
            val articles = newsReaderPort.findNews(
                keyword = keyword,
                from = window.from,
                to = window.to,
                limit = maxArticlesPerKeyword
            )

            // 요약할 뉴스가 없는 것은 장애가 아니다. 실패로 세면 뉴스가 뜸한 키워드 하나가 watermark 전체를 붙잡는다.
            if (articles.isEmpty()) {
                return SkippedKeywordOutcome(AiSkipReason.NO_INPUT)
            }

            val succeeded = summarizeCollectedNews(keyword, articles)
            resetKeywordFailures(quarantine, now)

            succeeded
        } catch (error: CancellationException) {
            // coroutine 취소는 요약 실패가 아니므로 결과로 변환하지 않는다.
            throw error
        } catch (error: Exception) {
            recordFailedKeyword(quarantine, keyword, error, now)
        }
    }

    /**
     * 실패를 분류해 실행 기록에 남길 원인을 정하고, 시도한 후보 전부가 입력 탓으로 끝났을 때만 격리 카운트를 올린다.
     *
     * 격리는 자동 해제가 없어 오판 비용이 호출 비용보다 크다. 한 모델의 거부나 provider 장애로 정상 키워드가 영구 격리되면 안 된다.
     */
    private suspend fun recordFailedKeyword(
        quarantine: KeywordQuarantine?,
        keyword: AiKeyword,
        error: Exception,
        now: Instant
    ): FailedKeywordOutcome {
        val failure = (error as? LlmProviderException)?.failure
        val reason = failureReasonOf(error)

        if (keywordBound(error)) {
            recordKeywordFailure(quarantine, keyword, reason, now)
        } else {
            log.warn(
                "News summary failed by infrastructure error. Keyword quarantine counter is not updated: keyword={}, reason={}",
                keyword.value,
                reason,
                error
            )
        }

        return FailedKeywordOutcome(
            reason = reason,
            abortsRun = failure != null && abortsRun(failure)
        )
    }

    private suspend fun summarizeCollectedNews(
        keyword: AiKeyword,
        articles: List<NewsArticle>
    ): SucceededKeywordOutcome {
        // newsHash는 같은 keyword/news id 묶음을 식별하는 LLM 호출 전 cache key다.
        // 조회 구간은 실행마다 달라지므로 hash에 넣지 않는다. 그래야 실패 후 재시도에서 성공분을 재사용한다.
        val newsHash = NewsHash.calculate(
            keyword = keyword,
            sourceNewsIds = articles.map { it.id }
        )
        val preparedLlm = llmProviderPort.prepareNewsSummary()
        val existingSummary = newsSummaryPersistencePort.findByUniqueKey(
            keyword = keyword,
            newsHash = newsHash,
            promptVersion = preparedLlm.plan.promptVersion
        )

        return existingSummary?.let(::reuseSummary)
            ?: generateSummary(keyword, articles, newsHash, preparedLlm)
    }

    /**
     * 기존 요약을 재사용하면 이번 실행에서는 LLM을 호출하지 않으므로 token 사용량도 0으로 남긴다.
     */
    private fun reuseSummary(existingSummary: NewsSummary): SucceededKeywordOutcome {
        return SucceededKeywordOutcome(
            summary = SummarizedNewsResult.from(existingSummary, reused = true),
            metadata = LlmGenerationMetadata(
                provider = existingSummary.provider,
                requestedModel = existingSummary.requestedModel,
                model = existingSummary.model,
                promptVersion = existingSummary.promptVersion,
                tokenUsage = TokenUsage(inputTokens = 0, outputTokens = 0)
            )
        )
    }

    private suspend fun generateSummary(
        keyword: AiKeyword,
        articles: List<NewsArticle>,
        newsHash: String,
        preparedLlm: PreparedLlmNewsSummary
    ): SucceededKeywordOutcome {
        val llmResult = preparedLlm.summarize(
            keyword = keyword,
            articles = articles
        )
        val now = Instant.now(clock)
        val summary = NewsSummary.create(
            keyword = keyword,
            sourceNewsIds = articles.map { it.id },
            newsHash = newsHash,
            title = llmResult.title,
            content = llmResult.content,
            sentiment = llmResult.sentiment,
            provider = llmResult.metadata.provider,
            model = llmResult.metadata.model,
            requestedModel = llmResult.metadata.requestedModel,
            promptVersion = llmResult.metadata.promptVersion,
            tokenUsage = llmResult.metadata.tokenUsage,
            createdAt = now
        )
        val event = SummaryCreatedEvent.from(summary)
        // 선조회 이후 다른 요청이 먼저 저장했으면, 새로 저장하지 않고 기존 요약을 사용한다.
        val savedSummary = newsSummaryPersistencePort.saveOrFindExisting(
            newsSummary = summary,
            outbox = event.toOutbox(payload = eventSerializer.serialize(event), now = now)
        )

        return SucceededKeywordOutcome(
            summary = SummarizedNewsResult.from(savedSummary, reused = savedSummary.id != summary.id),
            metadata = llmResult.metadata
        )
    }

    private suspend fun completeRun(
        startedRun: AiRun,
        outcome: NewsSummaryOutcome,
        watermarkAdvanced: Boolean
    ): AiRun {
        return aiRunPersistencePort.save(
            startedRun.complete(
                succeededCount = outcome.succeededCount,
                failureCount = outcome.failureCount,
                failureReason = outcome.failureReason,
                provider = outcome.metadata?.provider,
                model = outcome.metadata?.model,
                promptVersion = outcome.metadata?.promptVersion,
                finishedAt = Instant.now(clock),
                skippedCount = outcome.skippedCount,
                skipReason = outcome.skipReason,
                watermarkAdvanced = watermarkAdvanced
            )
        )
    }

    private fun resolveWindow(
        request: SummaryWindowRequest,
        watermark: Instant?,
        now: Instant
    ): ResolvedSummaryWindow {
        return when (request) {
            // 수동 실행은 지정 구간을 그대로 쓴다. 지정하지 않으면 collector가 전체 기간을 조회한다.
            is ExplicitSummaryWindowRequest -> ResolvedSummaryWindow(from = request.from, to = request.to)

            is WatermarkSummaryWindowRequest -> {
                val resolved = SummaryWindow.resolve(
                    watermark = watermark,
                    now = now,
                    overlap = request.overlap,
                    maxLookback = request.maxLookback
                )

                // maxLookback 하한에 걸리면 그 앞 구간은 어느 실행에도 들어가지 않는다. 조용히 사라지지 않게 남긴다.
                if (resolved.truncated) {
                    log.warn(
                        "News summary window truncated by max lookback. Skipped range is not processed: skippedFrom={}, windowFrom={}, watermark={}",
                        resolved.skippedFrom,
                        resolved.from,
                        watermark
                    )
                }

                ResolvedSummaryWindow(from = resolved.from, to = resolved.to)
            }
        }
    }

    private suspend fun advanceWatermarkIfCompleted(
        request: SummaryWindowRequest,
        watermark: SummaryWatermark?,
        window: ResolvedSummaryWindow,
        failureCount: Int,
        now: Instant
    ): Boolean {
        // 임의 구간을 지정한 수동 실행이 watermark를 움직이면 지정하지 않은 구간까지 처리된 것으로 기록된다.
        if (request !is WatermarkSummaryWindowRequest) {
            return false
        }
        if (failureCount > 0) {
            return false
        }

        val advanceTo = window.to ?: return false
        val advanced = if (watermark == null) {
            SummaryWatermark.initial(targetType = TARGET_TYPE, position = advanceTo, updatedAt = now)
        } else {
            // 전진하지 않았으면 저장하지 않고 그대로 기록한다. 이 경로는 시각 역행이나 미래 watermark 신호다.
            watermark.advanceTo(advanceTo, now) ?: run {
                log.warn(
                    "Watermark not advanced because requested position is not after current: requested={}, current={}",
                    advanceTo,
                    watermark.position
                )
                return false
            }
        }
        summaryWatermarkPersistencePort.save(advanced)

        return true
    }

    private suspend fun recordKeywordFailure(
        quarantine: KeywordQuarantine?,
        keyword: AiKeyword,
        reason: AiFailureReason,
        now: Instant
    ) {
        val tracked = quarantine ?: KeywordQuarantine.track(
            targetType = TARGET_TYPE,
            keyword = keyword,
            updatedAt = now
        )
        val updated = tracked.recordFailure(
            reason = reason,
            failureThreshold = quarantinePolicy.failureThreshold,
            updatedAt = now
        )
        // 격리로 넘어간 순간에만 알릴 이벤트를 남긴다.
        if (updated.isQuarantined && !tracked.isQuarantined) {
            val event = KeywordQuarantinedEvent.from(updated)
            keywordQuarantinePersistencePort.saveQuarantined(
                quarantine = updated,
                outbox = event.toOutbox(payload = eventSerializer.serialize(event), now = now)
            )
            log.error(
                "Keyword quarantined after consecutive news summary failures: keyword={}, consecutiveFailures={}, lastFailureReason={}",
                keyword.value,
                updated.consecutiveFailures,
                reason
            )
        } else {
            keywordQuarantinePersistencePort.save(updated)
        }
    }

    private suspend fun resetKeywordFailures(quarantine: KeywordQuarantine?, now: Instant) {
        // 실패한 적 없는 키워드까지 실행마다 기록하지 않는다.
        if (quarantine == null || !quarantine.needsReset()) {
            return
        }

        keywordQuarantinePersistencePort.save(quarantine.recordSuccess(now))
    }

    private companion object {
        val TARGET_TYPE = AiRunTargetType.NEWS_SUMMARY
        val log = LoggerFactory.getLogger(SummarizeNewsService::class.java)

        fun failureReasonOf(error: Throwable): AiFailureReason {
            return when (error) {
                is LlmProviderException -> failureReasonOf(error.failure)
                is NewsReaderException, is KeywordReaderException -> AiFailureReason.SERVER_ERROR
                // 도메인 불변식 위반은 이 키워드의 입력이나 응답에서 온다.
                is IllegalArgumentException -> AiFailureReason.INVALID_RESPONSE
                else -> AiFailureReason.UNKNOWN
            }
        }

        /** 실행 기록의 실패 원인. 실패 코드가 늘면 이 when이 컴파일에서 판정을 요구한다. */
        fun failureReasonOf(failure: LlmFailure): AiFailureReason {
            return when (failure.code) {
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

        /**
         * 키워드에 책임을 물을 수 있는 실패인지 판단한다.
         *
         * LLM 실패는 시도한 후보 전부가 입력 탓으로 끝났을 때만 키워드 탓이다.
         * 판별할 수 없는 실패는 인프라 쪽으로 본다. 잘못 세면 정상 키워드가 영구 격리되고, 놓치면 다음 실행이 다시 시도한다.
         */
        fun keywordBound(error: Throwable): Boolean {
            return when (error) {
                is LlmProviderException -> error.allInput
                is NewsReaderException, is KeywordReaderException -> false
                is IllegalArgumentException -> true
                else -> false
            }
        }

        /**
         * 이번 실행의 남은 키워드까지 막는 상태인지 판단한다.
         *
         * 실제 호출이 한 건도 나가지 못했다는 것은 후보 전부가 차단·hold·cooldown이라는 뜻이고, 이번 tick 안에서 풀리지 않는다.
         * 실제 호출이 있었으면 한 모델이 rate limit이어도 다른 후보나 다음 키워드는 시도해 볼 수 있다.
         */
        fun abortsRun(failure: LlmFailure): Boolean {
            return !failure.fromActualCall
        }
    }
}
