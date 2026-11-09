package me.rgunny.kachi.story.application.service.close

import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.story.StoryPersistencePort
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryStatus
import me.rgunny.kachi.story.fake.InMemoryCandidateIndexPort
import me.rgunny.kachi.story.fake.InMemoryStoryStore
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.fixture.StoryTestFixture.article
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 닫기 규칙을 fake 포트로 검증한다.
 *
 * 기준은 close-after 48h이고, 조용한 story는 마지막 기사 발행이 49시간 전이다.
 */
@DisplayName("CloseIdleStoriesService")
class CloseIdleStoriesServiceTest {
    private val store = InMemoryStoryStore()
    private val index = InMemoryCandidateIndexPort()

    @Test
    @DisplayName("조용한 OPEN story를 닫고 그 벡터를 색인에서 지운다")
    fun closeIdleStoryAndDeleteVectors() = runBlocking {
        val idle = seedStory(IDLE_STORY_ID, IDLE_NEWS_ID, quietFor = Duration.ofHours(49))
        seedStory(RECENT_STORY_ID, RECENT_NEWS_ID, quietFor = Duration.ofHours(1))

        val result = service().closeIdleStories()

        assertEquals(1, result.closedCount)
        assertEquals(NOW.minus(Duration.ofHours(48)), result.threshold)
        val closed = store.stories[idle.id]!!
        assertEquals(StoryStatus.CLOSED, closed.status)
        assertEquals(NOW, closed.closedAt)
        assertEquals(idle.version + 1, closed.version)
        assertEquals(StoryStatus.OPEN, store.stories[RECENT_STORY_ID]!!.status)
        assertEquals(setOf(RECENT_NEWS_ID), index.points.keys)
    }

    @Test
    @DisplayName("마지막 기사 발행이 정확히 close-after 전인 story는 닫지 않는다")
    fun keepStoryOnThresholdBoundary() = runBlocking {
        seedStory(IDLE_STORY_ID, IDLE_NEWS_ID, quietFor = Duration.ofHours(48))

        val result = service().closeIdleStories()

        assertEquals(0, result.closedCount)
        assertEquals(StoryStatus.OPEN, store.stories[IDLE_STORY_ID]!!.status)
    }

    @Test
    @DisplayName("닫는 사이 story가 바뀌면 건너뛰고 벡터를 지우지 않는다")
    fun skipStoryOnVersionConflict() = runBlocking {
        seedStory(IDLE_STORY_ID, IDLE_NEWS_ID, quietFor = Duration.ofHours(49))
        val service = CloseIdleStoriesService(
            storyPersistencePort = ConflictingStoryStore(store),
            candidateIndexPort = index,
            policy = policy(),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.closeIdleStories()

        assertEquals(0, result.closedCount)
        assertEquals(1, result.conflictedCount)
        assertEquals(StoryStatus.OPEN, store.stories[IDLE_STORY_ID]!!.status)
        assertEquals(setOf(IDLE_NEWS_ID), index.points.keys)
    }

    @Test
    @DisplayName("벡터 삭제가 실패해도 닫힘은 유지되고 실패 수를 센다")
    fun keepClosedStoryWhenIndexDeleteFails() = runBlocking {
        seedStory(IDLE_STORY_ID, IDLE_NEWS_ID, quietFor = Duration.ofHours(49))
        val service = CloseIdleStoriesService(
            storyPersistencePort = store,
            candidateIndexPort = FailingCandidateIndexPort(index),
            policy = policy(),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.closeIdleStories()

        assertEquals(1, result.closedCount)
        assertEquals(1, result.indexDeleteFailureCount)
        assertEquals(StoryStatus.CLOSED, store.stories[IDLE_STORY_ID]!!.status)
    }

    @Test
    @DisplayName("batch-limit까지만 닫는다")
    fun closeUpToBatchLimit() = runBlocking {
        seedStory(IDLE_STORY_ID, IDLE_NEWS_ID, quietFor = Duration.ofHours(50))
        seedStory(RECENT_STORY_ID, RECENT_NEWS_ID, quietFor = Duration.ofHours(49))

        val result = service(batchLimit = 1).closeIdleStories()

        assertEquals(1, result.closedCount)
        val closedCount = store.stories.values.count { it.status == StoryStatus.CLOSED }
        assertEquals(1, closedCount)
    }

    private fun service(batchLimit: Int = 100): CloseIdleStoriesService {
        return CloseIdleStoriesService(
            storyPersistencePort = store,
            candidateIndexPort = index,
            policy = policy(batchLimit),
            clock = StoryTestFixture.CLOCK
        )
    }

    private fun policy(batchLimit: Int = 100): StoryClosePolicy {
        return StoryClosePolicy(closeAfter = Duration.ofHours(48), batchLimit = batchLimit)
    }

    /** 마지막 기사 발행이 [quietFor] 전인 story를 저장하고 벡터도 색인에 넣는다. */
    private fun seedStory(storyId: StoryId, newsId: NewsId, quietFor: Duration): Story = runBlocking {
        val lastArticle: StoryArticle = article(
            newsId = newsId,
            storyId = storyId,
            publishedAt = NOW.minus(quietFor),
            collectedAt = NOW.minus(quietFor)
        )
        val story = Story.open(lastArticle, now = NOW.minus(quietFor))
        store.seed(story, lastArticle)
        index.upsert(listOf(IndexedArticle.from(lastArticle)))

        assertNotNull(store.stories[storyId])
        assertTrue(index.points.containsKey(newsId))

        story
    }

    /** version 조건부 쓰기가 항상 지는 저장소. */
    private class ConflictingStoryStore(
        private val delegate: InMemoryStoryStore
    ) : StoryPersistencePort by delegate {

        override suspend fun update(story: Story, expectedVersion: Long): Boolean = false
    }

    /** story 단위 삭제가 항상 실패하는 색인. */
    private class FailingCandidateIndexPort(
        private val delegate: InMemoryCandidateIndexPort
    ) : CandidateIndexPort by delegate {

        override suspend fun deleteByStory(storyId: StoryId) {
            throw IllegalStateException("index unavailable")
        }
    }

    private companion object {
        val IDLE_STORY_ID: StoryId = StoryId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000a1"))
        val IDLE_NEWS_ID: NewsId = NewsId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000b1"))
        val RECENT_STORY_ID: StoryId = StoryId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000a2"))
        val RECENT_NEWS_ID: NewsId = NewsId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000b2"))
    }
}
