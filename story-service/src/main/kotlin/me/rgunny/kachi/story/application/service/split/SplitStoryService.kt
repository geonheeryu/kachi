package me.rgunny.kachi.story.application.service.split

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.story.application.exception.StoryOperationErrorCode
import me.rgunny.kachi.story.application.exception.StoryOperationException
import me.rgunny.kachi.story.application.port.inbound.split.SplitStoryUseCase
import me.rgunny.kachi.story.application.port.inbound.split.model.SplitStoryCommand
import me.rgunny.kachi.story.application.port.inbound.split.model.SplitStoryResult
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.story.StoryArticlePersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.StoryPersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.StoryReorganizePersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.model.ReorganizeOutcome
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * OPEN story에서 지정한 기사를 새 story로 떼어 내는 유스케이스.
 *
 * 새 story는 옮긴 기사에서, 원 story는 남은 기사에서 파생 상태를 다시 계산한다.
 * 쓰기는 원 story의 version 조건부라, 그 사이 story가 바뀌면 실패로 알린다.
 * 기사 사본이 임베딩을 갖고 있어 색인 이전에 재임베딩은 없다.
 */
@Service
class SplitStoryService(
    private val storyPersistencePort: StoryPersistencePort,
    private val storyArticlePersistencePort: StoryArticlePersistencePort,
    private val reorganizePersistencePort: StoryReorganizePersistencePort,
    private val candidateIndexPort: CandidateIndexPort,
    private val clock: Clock
) : SplitStoryUseCase {

    override suspend fun split(command: SplitStoryCommand): SplitStoryResult {
        val now = Instant.now(clock)
        val story = openStoryOf(command.storyId)
        val articles = storyArticlePersistencePort.findByStory(story.id)
        val movedNewsIds = movedNewsIdsOf(command, articles)

        val newStoryId = StoryId.newId()
        val moved = articles.filter { it.newsId in movedNewsIds }.map { it.reassign(newStoryId) }
        val remaining = articles.filterNot { it.newsId in movedNewsIds }
        val newStory = Story.open(moved.first(), now, parentStoryId = story.id).recompose(moved, now)
        val original = story.recompose(remaining, now)

        val outcome = reorganizePersistencePort.split(
            original = original,
            expectedVersion = story.version,
            newStory = newStory,
            movedNewsIds = moved.map { it.newsId }
        )
        if (outcome == ReorganizeOutcome.STORY_CHANGED) {
            throw StoryOperationException(StoryOperationErrorCode.REORGANIZE_CONFLICT, "storyId=${story.id.value}")
        }

        reindexMoved(moved)
        logSplit(original, newStory, moved.size)

        return SplitStoryResult(
            original = original,
            newStory = newStory,
            movedArticleCount = moved.size
        )
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

    private fun movedNewsIdsOf(command: SplitStoryCommand, articles: List<StoryArticle>): Set<NewsId> {
        val movedNewsIds = command.newsIds.toSet()
        if (movedNewsIds.isEmpty()) {
            throw StoryOperationException(StoryOperationErrorCode.SPLIT_ARTICLES_REQUIRED, "storyId=${command.storyId.value}")
        }
        val missing = movedNewsIds - articles.map { it.newsId }.toSet()
        if (missing.isNotEmpty()) {
            throw StoryOperationException(
                errorCode = StoryOperationErrorCode.SPLIT_ARTICLES_NOT_IN_STORY,
                detail = "storyId=${command.storyId.value}, newsIds=${missing.map { it.value }}"
            )
        }
        if (movedNewsIds.size == articles.size) {
            throw StoryOperationException(StoryOperationErrorCode.SPLIT_ALL_ARTICLES_REJECTED, "storyId=${command.storyId.value}")
        }

        return movedNewsIds
    }

    private suspend fun reindexMoved(moved: List<StoryArticle>) {
        runCatching { candidateIndexPort.upsert(moved.map(IndexedArticle::from)) }
            .onFailure { error ->
                log.warn(
                    "Failed to reindex articles of split story: newStoryId={}, articleCount={}",
                    moved.first().storyId.value,
                    moved.size,
                    error
                )
            }
    }

    private fun logSplit(original: Story, newStory: Story, movedCount: Int) {
        log.info(
            "Story split: storyId={}, newStoryId={}, movedCount={}, remainingCount={}",
            original.id.value,
            newStory.id.value,
            movedCount,
            original.articleCount
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(SplitStoryService::class.java)
    }
}
