package me.rgunny.kachi.story.application.service.split

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.exception.StoryOperationErrorCode
import me.rgunny.kachi.story.application.exception.StoryOperationException
import me.rgunny.kachi.story.application.port.inbound.split.model.SplitStoryCommand
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.story.model.ReorganizeOutcome
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryKeyword
import me.rgunny.kachi.story.domain.StoryStatus
import me.rgunny.kachi.story.fake.InMemoryCandidateIndexPort
import me.rgunny.kachi.story.fake.InMemoryStoryStore
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.fixture.StoryTestFixture.article
import me.rgunny.kachi.story.fixture.StoryTestFixture.embedding
import me.rgunny.kachi.story.fixture.StoryTestFixture.newsId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SplitStoryService")
class SplitStoryServiceTest {
    private val store = InMemoryStoryStore()
    private val index = InMemoryCandidateIndexPort()

    @Test
    @DisplayName("옮긴 기사와 남은 기사에서 두 story의 파생 상태를 다시 계산한다")
    fun recomputeBothStories() = runBlocking {
        val (story, articles) = seedStory(
            article(newsId = newsId(1), embedding = embedding(1f, 0f), keywords = listOf("nvidia")),
            article(newsId = newsId(2), embedding = embedding(0f, 1f), keywords = listOf("ai"), publishedAt = NOW.minus(Duration.ofHours(1))),
            article(newsId = newsId(3), embedding = embedding(0f, 1f), keywords = listOf("ai"), publishedAt = NOW.minus(Duration.ofHours(2)))
        )

        val result = service().split(SplitStoryCommand(story.id, listOf(newsId(2), newsId(3))))

        assertEquals(2, result.movedArticleCount)
        val original = store.stories.getValue(story.id)
        assertEquals(1, original.articleCount)
        assertEquals(embedding(1f, 0f), original.centroid)
        assertEquals(setOf(StoryKeyword.of("nvidia")), original.keywords)
        assertEquals(articles[0].publishedAt, original.lastArticleAt)
        assertEquals(story.version + 1, original.version)

        val newStory = store.stories.getValue(result.newStory.id)
        assertEquals(StoryStatus.OPEN, newStory.status)
        assertEquals(2, newStory.articleCount)
        assertEquals(embedding(0f, 1f), newStory.centroid)
        assertEquals(setOf(StoryKeyword.of("ai")), newStory.keywords)
        assertEquals(articles[1].publishedAt, newStory.lastArticleAt)
        assertEquals(story.id, newStory.parentStoryId)
        assertEquals(NOW, newStory.openedAt)
        assertTrue(store.articles.values.filter { it.storyId == newStory.id }.map { it.newsId }.containsAll(listOf(newsId(2), newsId(3))))
    }

    @Test
    @DisplayName("커밋 후 옮긴 기사를 새 storyId payload로 다시 색인한다")
    fun reindexMovedArticles() = runBlocking {
        val (story, _) = seedStory(
            article(newsId = newsId(1), embedding = embedding(1f, 0f)),
            article(newsId = newsId(2), embedding = embedding(0f, 1f))
        )

        val result = service().split(SplitStoryCommand(story.id, listOf(newsId(2))))

        assertEquals(result.newStory.id, index.points.getValue(newsId(2)).storyId)
        assertEquals(story.id, index.points.getValue(newsId(1)).storyId)
    }

    @Test
    @DisplayName("없는 story와 닫힌 story는 거부한다")
    fun rejectMissingOrClosedStory() = runBlocking {
        val (story, _) = seedStory(article(newsId = newsId(1)), article(newsId = newsId(2)))
        store.stories[story.id] = store.stories.getValue(story.id).close(NOW)

        val notFound = assertFailsWith<StoryOperationException> {
            service().split(SplitStoryCommand(StoryId.newId(), listOf(newsId(1))))
        }
        assertEquals(StoryOperationErrorCode.STORY_NOT_FOUND, notFound.errorCode)

        val notOpen = assertFailsWith<StoryOperationException> {
            service().split(SplitStoryCommand(story.id, listOf(newsId(1))))
        }
        assertEquals(StoryOperationErrorCode.STORY_NOT_OPEN, notOpen.errorCode)
    }

    @Test
    @DisplayName("빈 목록, 소속 아닌 기사, 전체 분리는 거부한다")
    fun rejectInvalidNewsIds() = runBlocking {
        val (story, _) = seedStory(article(newsId = newsId(1)), article(newsId = newsId(2)))

        val required = assertFailsWith<StoryOperationException> {
            service().split(SplitStoryCommand(story.id, emptyList()))
        }
        assertEquals(StoryOperationErrorCode.SPLIT_ARTICLES_REQUIRED, required.errorCode)

        val notInStory = assertFailsWith<StoryOperationException> {
            service().split(SplitStoryCommand(story.id, listOf(newsId(1), newsId(9))))
        }
        assertEquals(StoryOperationErrorCode.SPLIT_ARTICLES_NOT_IN_STORY, notInStory.errorCode)

        val all = assertFailsWith<StoryOperationException> {
            service().split(SplitStoryCommand(story.id, listOf(newsId(1), newsId(2))))
        }
        assertEquals(StoryOperationErrorCode.SPLIT_ALL_ARTICLES_REJECTED, all.errorCode)
        assertTrue(store.splitCalls.isEmpty())
    }

    @Test
    @DisplayName("분리 중 story가 바뀌면 실패로 알린다")
    fun reportConflictOnVersionChange() = runBlocking {
        val (story, _) = seedStory(article(newsId = newsId(1)), article(newsId = newsId(2)))
        store.splitOutcomes += ReorganizeOutcome.STORY_CHANGED

        val conflict = assertFailsWith<StoryOperationException> {
            service().split(SplitStoryCommand(story.id, listOf(newsId(2))))
        }

        assertEquals(StoryOperationErrorCode.REORGANIZE_CONFLICT, conflict.errorCode)
        assertEquals(story.version, store.stories.getValue(story.id).version)
    }

    @Test
    @DisplayName("색인 이전이 실패해도 분리는 유지된다")
    fun keepSplitWhenReindexFails() = runBlocking {
        val (story, _) = seedStory(article(newsId = newsId(1)), article(newsId = newsId(2)))
        val service = SplitStoryService(
            storyPersistencePort = store,
            storyArticlePersistencePort = store,
            reorganizePersistencePort = store,
            candidateIndexPort = FailingUpsertIndexPort(index),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.split(SplitStoryCommand(story.id, listOf(newsId(2))))

        assertEquals(result.newStory.id, store.articles.getValue(newsId(2)).storyId)
        assertEquals(story.id, index.points.getValue(newsId(2)).storyId)
    }

    private fun service(): SplitStoryService {
        return SplitStoryService(
            storyPersistencePort = store,
            storyArticlePersistencePort = store,
            reorganizePersistencePort = store,
            candidateIndexPort = index,
            clock = StoryTestFixture.CLOCK
        )
    }

    /**
     * 주어진 기사들로 story 하나를 저장소와 색인에 심는다.
     */
    private suspend fun seedStory(vararg seeds: StoryArticle): Pair<Story, List<StoryArticle>> {
        val storyId = StoryId.newId()
        val articles = seeds.map { it.reassign(storyId) }
        val openedAt = NOW.minus(Duration.ofHours(3))
        val story = articles.drop(1).fold(Story.open(articles.first(), openedAt)) { acc, article -> acc.attach(article, openedAt) }
        store.seed(story, *articles.toTypedArray())
        index.upsert(articles.map { IndexedArticle.from(it) })

        return story to articles
    }

    /** upsert가 항상 실패하는 색인. */
    private class FailingUpsertIndexPort(
        private val delegate: InMemoryCandidateIndexPort
    ) : CandidateIndexPort by delegate {

        override suspend fun upsert(articles: List<IndexedArticle>) {
            throw IllegalStateException("index unavailable")
        }
    }
}
