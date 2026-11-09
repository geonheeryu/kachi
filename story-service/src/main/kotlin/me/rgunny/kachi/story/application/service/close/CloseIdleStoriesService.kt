package me.rgunny.kachi.story.application.service.close

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.story.application.port.inbound.close.CloseIdleStoriesUseCase
import me.rgunny.kachi.story.application.port.inbound.close.model.CloseIdleStoriesResult
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.story.StoryPersistencePort
import me.rgunny.kachi.story.domain.Story
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 오래 조용한 OPEN story를 닫고 그 벡터를 색인에서 빼는 유스케이스.
 *
 * 닫기는 version 조건부 쓰기라, 그 사이 기사가 붙은 story는 건너뛴다.
 * 벡터 삭제는 닫기가 저장된 뒤에만 시도한다.
 */
@Service
class CloseIdleStoriesService(
    private val storyPersistencePort: StoryPersistencePort,
    private val candidateIndexPort: CandidateIndexPort,
    private val policy: StoryClosePolicy,
    private val clock: Clock
) : CloseIdleStoriesUseCase {

    override suspend fun closeIdleStories(): CloseIdleStoriesResult {
        val now = Instant.now(clock)
        val threshold = now.minus(policy.closeAfter)
        val idleStories = storyPersistencePort.findOpenWithLastArticleBefore(threshold, policy.batchLimit)

        var closedCount = 0
        var conflictedCount = 0
        var indexDeleteFailureCount = 0
        for (story in idleStories) {
            if (!storyPersistencePort.update(story.close(now), story.version)) {
                conflictedCount += 1
                continue
            }
            closedCount += 1
            if (!deleteVectors(story)) {
                indexDeleteFailureCount += 1
            }
        }

        return CloseIdleStoriesResult(
            threshold = threshold,
            closedCount = closedCount,
            conflictedCount = conflictedCount,
            indexDeleteFailureCount = indexDeleteFailureCount
        )
    }

    private suspend fun deleteVectors(story: Story): Boolean {
        return runCatching { candidateIndexPort.deleteByStory(story.id) }
            .onFailure { error ->
                log.warn("Failed to delete vectors of closed story: storyId={}", story.id.value, error)
            }
            .isSuccess
    }

    private companion object {
        val log = LoggerFactory.getLogger(CloseIdleStoriesService::class.java)
    }
}
