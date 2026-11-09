package me.rgunny.kachi.story.application.service.merge

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.story.application.exception.StoryOperationErrorCode
import me.rgunny.kachi.story.application.exception.StoryOperationException
import me.rgunny.kachi.story.application.port.inbound.merge.MergeOpenStoriesUseCase
import me.rgunny.kachi.story.application.port.inbound.merge.MergeStoryPairUseCase
import me.rgunny.kachi.story.application.port.inbound.merge.model.MergeOpenStoriesResult
import me.rgunny.kachi.story.application.port.inbound.merge.model.MergeStoryPairCommand
import me.rgunny.kachi.story.application.port.inbound.merge.model.MergeStoryPairResult
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateQuery
import me.rgunny.kachi.story.application.port.outbound.outbox.StoryOutboxEventSerializer
import me.rgunny.kachi.story.application.port.outbound.outbox.model.StoryMergedEvent
import me.rgunny.kachi.story.application.port.outbound.outbox.model.toOutbox
import me.rgunny.kachi.story.application.port.outbound.story.StoryPersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.StoryReorganizePersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.model.ReorganizeOutcome
import me.rgunny.kachi.story.application.service.assembly.AssemblyPolicy
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryStatus
import me.rgunny.kachi.story.domain.outbox.StoryOutbox
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * OPEN story 쌍을 합치는 유스케이스.
 *
 * 주기 병합은 최근에 연 story를 스캔해 centroid가 θ_high 이상 가까워진 쌍을 찾고, 생존자는 먼저 연 쪽(`openedAt` 동률이면 id 문자열이 작은 쪽)이다.
 * 운영자 병합은 지정한 쌍을 θ 검사 없이 합치고 지정한 쪽이 생존한다.
 * 쌍의 쓰기는 version 조건부라, 그 사이 어느 한쪽이 바뀐 쌍을 주기 병합은 건너뛰고 운영자 병합은 실패로 알린다.
 * 색인 이전 실패는 조립이 후보를 읽을 때 스스로 고치므로 기록만 남긴다.
 */
@Service
class MergeStoriesService(
    private val storyPersistencePort: StoryPersistencePort,
    private val candidateIndexPort: CandidateIndexPort,
    private val reorganizePersistencePort: StoryReorganizePersistencePort,
    private val eventSerializer: StoryOutboxEventSerializer,
    private val mergePolicy: StoryMergePolicy,
    private val assemblyPolicy: AssemblyPolicy,
    private val clock: Clock
) : MergeOpenStoriesUseCase, MergeStoryPairUseCase {

    override suspend fun mergeOpenStories(): MergeOpenStoriesResult {
        val now = Instant.now(clock)
        val scanned = storyPersistencePort.find(
            status = StoryStatus.OPEN,
            openedAfter = now.minus(mergePolicy.scanWindow),
            limit = mergePolicy.scanLimit
        )

        // 이번 틱에서 갱신된 story의 최신 상태. 스캔 목록은 틱 시작 시점의 스냅샷이라 병합마다 여기서 갈아 끼운다.
        val current = scanned.associateBy { it.id }.toMutableMap()
        val absorbed = mutableSetOf<StoryId>()

        // 이번 틱에서 이미 시도한 쌍. 경합으로 건너뛴 쌍은 상대 story 차례에서도 다시 시도하지 않는다.
        val attempted = mutableSetOf<Set<StoryId>>()
        var mergedCount = 0
        var conflictedCount = 0
        var indexReassignFailureCount = 0

        for (subjectId in scanned.map { it.id }) {
            if (subjectId in absorbed) {
                continue
            }

            for (partner in partnersOf(current.getValue(subjectId), absorbed, now)) {
                val subject = current.getValue(subjectId)
                if (partner.id in absorbed || subject.articleCount + partner.articleCount > assemblyPolicy.maxArticles) {
                    continue
                }
                if (!attempted.add(setOf(subject.id, partner.id))) {
                    continue
                }

                val (survivor, source) = survivorOf(subject, partner)
                val survivorAfter = survivor.absorb(source, now)
                val sourceAfter = source.mergeInto(survivor, now)

                val outcome = reorganizePersistencePort.merge(
                    target = survivorAfter,
                    source = sourceAfter,
                    expectedTargetVersion = survivor.version,
                    expectedSourceVersion = source.version,
                    outbox = outboxOf(sourceAfter, now)
                )
                if (outcome == ReorganizeOutcome.STORY_CHANGED) {
                    conflictedCount += 1
                    continue
                }

                mergedCount += 1
                absorbed += source.id
                current[survivorAfter.id] = survivorAfter
                if (!reassignIndex(source.id, survivorAfter.id)) {
                    indexReassignFailureCount += 1
                }
                logMerged(survivorAfter, sourceAfter)
                if (source.id == subjectId) {
                    break
                }
            }
        }

        return MergeOpenStoriesResult(
            scannedCount = scanned.size,
            mergedCount = mergedCount,
            conflictedCount = conflictedCount,
            indexReassignFailureCount = indexReassignFailureCount
        )
    }

    override suspend fun mergeStoryPair(command: MergeStoryPairCommand): MergeStoryPairResult {
        val now = Instant.now(clock)
        val (target, source) = pairOf(command)
        val survivorAfter = target.absorb(source, now)
        val sourceAfter = source.mergeInto(target, now)

        val outcome = reorganizePersistencePort.merge(
            target = survivorAfter,
            source = sourceAfter,
            expectedTargetVersion = target.version,
            expectedSourceVersion = source.version,
            outbox = outboxOf(sourceAfter, now)
        )
        if (outcome == ReorganizeOutcome.STORY_CHANGED) {
            throw StoryOperationException(
                errorCode = StoryOperationErrorCode.REORGANIZE_CONFLICT,
                detail = "storyId=${target.id.value}, mergedStoryId=${source.id.value}"
            )
        }

        reassignIndex(source.id, survivorAfter.id)
        logMerged(survivorAfter, sourceAfter)

        return MergeStoryPairResult(survivor = survivorAfter, mergedStoryId = source.id)
    }

    /**
     * [subject]와 합칠 수 있는 story를 가까운 순으로 세운다.
     *
     * 후보는 OPEN이고, centroid 모델이 같고, centroid 코사인이 θ_high 이상이고, 합산 기사 수가 상한 아래인 story다.
     */
    private suspend fun partnersOf(subject: Story, absorbed: Set<StoryId>, now: Instant): List<Story> {
        val hits = candidateIndexPort.search(
            CandidateQuery(
                embedding = subject.centroid,
                collectedAfter = now.minus(assemblyPolicy.candidateWindow),
                limit = assemblyPolicy.candidateLimit
            )
        )
        val partnerIds = hits.map { it.storyId }.toSet() - subject.id - absorbed
        if (partnerIds.isEmpty()) {
            return emptyList()
        }

        return storyPersistencePort.findByIds(partnerIds)
            .filter { it.status == StoryStatus.OPEN }
            .filter { it.centroid.model == subject.centroid.model }
            .filter { subject.centroid.cosine(it.centroid) >= assemblyPolicy.thetaHigh }
            .filter { subject.articleCount + it.articleCount <= assemblyPolicy.maxArticles }
            .sortedByDescending { subject.centroid.cosine(it.centroid) }
    }

    /**
     * 쌍에서 살아남을 쪽과 흡수될 쪽을 정한다.
     */
    private fun survivorOf(a: Story, b: Story): Pair<Story, Story> {
        val survivorFirst = when {
            a.openedAt != b.openedAt -> a.openedAt.isBefore(b.openedAt)
            else -> a.id.value.toString() < b.id.value.toString()
        }

        return if (survivorFirst) a to b else b to a
    }

    /**
     * 운영자가 지정한 병합 대상 두 story 검증
     *
     * θ 검사는 하지 않고 OPEN 여부, centroid 모델, 합산 기사 수 상한만 본다.
     */
    private suspend fun pairOf(command: MergeStoryPairCommand): Pair<Story, Story> {
        if (command.targetStoryId == command.sourceStoryId) {
            throw StoryOperationException(
                errorCode = StoryOperationErrorCode.MERGE_INCOMPATIBLE,
                detail = "같은 story입니다: ${command.targetStoryId.value}"
            )
        }
        val target = openStoryOf(command.targetStoryId)
        val source = openStoryOf(command.sourceStoryId)
        if (target.centroid.model != source.centroid.model) {
            throw StoryOperationException(
                errorCode = StoryOperationErrorCode.MERGE_INCOMPATIBLE,
                detail = "centroid 모델이 다릅니다: target=${target.centroid.model.code}, source=${source.centroid.model.code}"
            )
        }
        if (target.articleCount + source.articleCount > assemblyPolicy.maxArticles) {
            throw StoryOperationException(
                errorCode = StoryOperationErrorCode.MERGE_INCOMPATIBLE,
                detail = "합산 기사 수가 상한을 넘습니다: ${target.articleCount + source.articleCount} > ${assemblyPolicy.maxArticles}"
            )
        }

        return target to source
    }

    private suspend fun openStoryOf(storyId: StoryId): Story {
        val story = storyPersistencePort.findById(storyId)
            ?: throw StoryOperationException(StoryOperationErrorCode.STORY_NOT_FOUND, "storyId=${storyId.value}")
        if (story.status != StoryStatus.OPEN) {
            throw StoryOperationException(
                errorCode = StoryOperationErrorCode.STORY_NOT_OPEN,
                detail = "storyId=${storyId.value}, status=${story.status}"
            )
        }

        return story
    }

    private fun outboxOf(merged: Story, now: Instant): StoryOutbox {
        val event = StoryMergedEvent.from(merged)

        return event.toOutbox(payload = eventSerializer.serialize(event), now = now)
    }

    private suspend fun reassignIndex(from: StoryId, to: StoryId): Boolean {
        return runCatching { candidateIndexPort.reassignStory(from, to) }
            .onFailure { error ->
                log.warn(
                    "Failed to reassign index entries of merged story: mergedId={}, survivorId={}",
                    from.value,
                    to.value,
                    error
                )
            }
            .isSuccess
    }

    private fun logMerged(survivor: Story, source: Story) {
        log.info(
            "Stories merged: survivorId={}, mergedId={}, articleCount={}",
            survivor.id.value,
            source.id.value,
            survivor.articleCount
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(MergeStoriesService::class.java)
    }
}
