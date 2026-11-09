package me.rgunny.kachi.story.application.service.index

import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.fake.InMemoryCandidateIndexPort
import me.rgunny.kachi.story.fake.InMemoryStoryStore
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.fixture.StoryTestFixture.article
import me.rgunny.kachi.story.fixture.StoryTestFixture.assemblyPolicy
import me.rgunny.kachi.story.fixture.StoryTestFixture.embedding
import me.rgunny.kachi.story.fixture.StoryTestFixture.newsId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RebuildCandidateIndexService")
class RebuildCandidateIndexServiceTest {
    private val store = InMemoryStoryStore()
    private val index = InMemoryCandidateIndexPort()
    private val service = RebuildCandidateIndexService(
        storyArticlePersistencePort = store,
        storyPersistencePort = store,
        candidateIndexPort = index,
        assemblyPolicy = assemblyPolicy(),
        clock = StoryTestFixture.CLOCK
    )

    @Test
    @DisplayName("페이지 크기를 넘는 기사를 끝까지 순회해 다시 색인한다")
    fun traverseAllPages() = runBlocking {
        val count = RebuildCandidateIndexService.PAGE_SIZE + 1
        seedStory(articleCount = count)

        val result = service.rebuild()

        assertEquals(count, result.scannedCount)
        assertEquals(count, result.indexedCount)
        assertEquals(0, result.skippedClosedCount)
        assertEquals(count, index.points.size)
    }

    @Test
    @DisplayName("CLOSED story의 기사는 색인하지 않는다")
    fun skipArticlesOfClosedStory() = runBlocking {
        seedStory(articleCount = 2, firstNewsIdOffset = 0)
        val closed = seedStory(articleCount = 1, firstNewsIdOffset = 10)
        store.stories[closed.id] = store.stories.getValue(closed.id).close(NOW)

        val result = service.rebuild()

        assertEquals(3, result.scannedCount)
        assertEquals(2, result.indexedCount)
        assertEquals(1, result.skippedClosedCount)
        assertTrue(index.points.values.none { it.storyId == closed.id })
    }

    @Test
    @DisplayName("색인을 비운 뒤 저장된 기사로 다시 채운다")
    fun clearIndexBeforeReindexing() = runBlocking {
        val story = seedStory(articleCount = 1)
        val stale = article(
            newsId = NewsId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000ff")),
            storyId = StoryId.newId(),
            collectedAt = NOW.minus(Duration.ofHours(1))
        )
        index.upsert(listOf(IndexedArticle.from(stale)))

        val result = service.rebuild()

        assertEquals(1, result.indexedCount)
        assertTrue(index.points.values.all { it.storyId == story.id })
    }

    /**
     * 연속된 기사 id로 story 하나를 저장소에 심는다.
     */
    private fun seedStory(articleCount: Int, firstNewsIdOffset: Int = 0): Story {
        val storyId = StoryId.newId()
        val articles = (0 until articleCount).map { i ->
            article(
                newsId = newsId(firstNewsIdOffset + i),
                embedding = embedding(1f),
                storyId = storyId,
                collectedAt = NOW.minus(Duration.ofHours(1))
            )
        }
        val openedAt = NOW.minus(Duration.ofHours(2))
        val story = articles.drop(1).fold(Story.open(articles.first(), openedAt)) { acc, article -> acc.attach(article, openedAt) }
        store.seed(story, *articles.toTypedArray())

        return story
    }

}
