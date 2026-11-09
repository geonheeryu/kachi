package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.exception.CandidateIndexException
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateQuery
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.domain.ArticleLanguage
import me.rgunny.kachi.story.domain.Embedding
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.index.CandidateIndexFailureCode
import me.rgunny.kachi.story.fixture.StoryTestFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.qdrant.QdrantContainer

/**
 * 실제 Qdrant 컨테이너로 adapter의 쓰기·검색·삭제 계약을 보는 통합 테스트.
 */
@DisplayName("QdrantCandidateIndexAdapter 통합 테스트")
class QdrantCandidateIndexAdapterIntegrationTest {

    private val now = StoryTestFixture.NOW
    private val storyA = StoryId.newId()
    private val storyB = StoryId.newId()
    private val collection = QdrantCollection(client, "story-articles-test-" + UUID.randomUUID(), DIMENSION)
    private val adapter = QdrantCandidateIndexAdapter(client, collection)

    @BeforeEach
    fun prepareCollection() = runBlocking {
        collection.ensure()
    }

    @Nested
    @DisplayName("ensure()")
    inner class Ensure {

        @Test
        @DisplayName("두 번 불러도 같은 컬렉션이다")
        fun idempotent() = runBlocking {
            collection.ensure()

            assertTrue(client.collectionExistsAsync(collection.name).await())
            val size = client.getCollectionInfoAsync(collection.name).await().config.params.vectorsConfig.params.size
            assertEquals(DIMENSION.toLong(), size)
        }

        @Test
        @DisplayName("이미 있는 컬렉션의 벡터 차원이 다르면 실패한다")
        fun rejectDimensionMismatch() = runBlocking {
            val other = QdrantCollection(client, collection.name, DIMENSION + 1)

            val error = assertFailsWith<IllegalStateException> { other.ensure() }
            assertTrue(error.message!!.contains("expected=${DIMENSION + 1}, actual=$DIMENSION"))
        }
    }

    @Nested
    @DisplayName("upsert()와 search()")
    inner class UpsertAndSearch {

        @Test
        @DisplayName("검색 점수는 Embedding.cosine과 1e-4 안이고 유사도 내림차순이다")
        fun scoreMatchesCosine() = runBlocking {
            val subject = StoryTestFixture.embedding(1f, 0f, 0f)
            val near = article(newsId(1), StoryTestFixture.embedding(0.9f, 0.1f, 0f), storyA)
            val far = article(newsId(2), StoryTestFixture.embedding(0.1f, 0.9f, 0f), storyB)
            adapter.upsert(listOf(far, near))

            val hits = adapter.search(CandidateQuery(embedding = subject, collectedAfter = now.minus(Duration.ofDays(3)), limit = 10))

            assertEquals(listOf(near.newsId, far.newsId), hits.map { it.newsId })
            assertEquals(listOf(storyA, storyB), hits.map { it.storyId })
            assertTrue(abs(hits[0].similarity - subject.cosine(near.embedding)) < 1e-4)
            assertTrue(abs(hits[1].similarity - subject.cosine(far.embedding)) < 1e-4)
        }

        @Test
        @DisplayName("collectedAfter보다 앞선 기사는 후보에서 빠진다")
        fun filterByCollectedAfter() = runBlocking {
            val recent = article(newsId(1), StoryTestFixture.embedding(1f), storyA, collectedAt = now)
            val old = article(newsId(2), StoryTestFixture.embedding(1f), storyA, collectedAt = now.minus(Duration.ofDays(4)))
            adapter.upsert(listOf(recent, old))

            val hits = adapter.search(query(collectedAfter = now.minus(Duration.ofDays(3))))

            assertEquals(listOf(recent.newsId), hits.map { it.newsId })
        }

        @Test
        @DisplayName("language를 주면 그 언어만, 주지 않으면 전부 후보다")
        fun filterByLanguage() = runBlocking {
            val korean = article(newsId(1), StoryTestFixture.embedding(1f), storyA, language = "ko")
            val english = article(newsId(2), StoryTestFixture.embedding(1f), storyA, language = "en")
            adapter.upsert(listOf(korean, english))

            val onlyEnglish = adapter.search(query(language = ArticleLanguage.of("en")))
            val all = adapter.search(query())

            assertEquals(listOf(english.newsId), onlyEnglish.map { it.newsId })
            assertEquals(setOf(korean.newsId, english.newsId), all.map { it.newsId }.toSet())
        }

        @Test
        @DisplayName("limit만큼만 돌려준다")
        fun limit() = runBlocking {
            adapter.upsert((1..5).map { article(newsId(it), StoryTestFixture.embedding(1f, it * 0.1f), storyA) })

            assertEquals(3, adapter.search(query(limit = 3)).size)
        }

        @Test
        @DisplayName("같은 newsId를 다시 넣으면 덮어쓴다")
        fun overwriteSameNewsId() = runBlocking {
            adapter.upsert(listOf(article(newsId(1), StoryTestFixture.embedding(1f), storyA)))
            adapter.upsert(listOf(article(newsId(1), StoryTestFixture.embedding(1f), storyB)))

            val hits = adapter.search(query())

            assertEquals(1, hits.size)
            assertEquals(storyB, hits.single().storyId)
        }

        @Test
        @DisplayName("100건을 넘는 입력도 전부 들어간다")
        fun upsertBeyondBatch() = runBlocking {
            adapter.upsert((1..150).map { article(newsId(it), StoryTestFixture.embedding(1f, it * 0.001f), storyA) })

            assertEquals(150, adapter.search(query(limit = 200)).size)
        }

        @Test
        @DisplayName("빈 입력은 호출하지 않는다")
        fun emptyUpsert() = runBlocking {
            adapter.upsert(emptyList())

            assertEquals(0, adapter.search(query()).size)
        }
    }

    @Nested
    @DisplayName("삭제와 재배정")
    inner class DeleteAndReassign {

        @Test
        @DisplayName("deleteByStory()는 그 story의 점만 지운다")
        fun deleteByStory() = runBlocking {
            adapter.upsert(listOf(article(newsId(1), StoryTestFixture.embedding(1f), storyA), article(newsId(2), StoryTestFixture.embedding(1f), storyB)))

            adapter.deleteByStory(storyA)

            assertEquals(listOf(newsId(2)), adapter.search(query()).map { it.newsId })
        }

        @Test
        @DisplayName("reassignStory()는 from의 점이 to를 가리키게 한다")
        fun reassignStory() = runBlocking {
            adapter.upsert(listOf(article(newsId(1), StoryTestFixture.embedding(1f), storyA), article(newsId(2), StoryTestFixture.embedding(1f), storyB)))

            adapter.reassignStory(from = storyA, to = storyB)

            assertEquals(listOf(storyB, storyB), adapter.search(query()).map { it.storyId })
        }

        @Test
        @DisplayName("deleteByNewsIds()는 지정한 점만 지우고 빈 목록은 호출하지 않는다")
        fun deleteByNewsIds() = runBlocking {
            adapter.upsert((1..3).map { article(newsId(it), StoryTestFixture.embedding(1f), storyA) })

            adapter.deleteByNewsIds(listOf(newsId(1), newsId(3)))
            adapter.deleteByNewsIds(emptyList())

            assertEquals(listOf(newsId(2)), adapter.search(query()).map { it.newsId })
        }

        @Test
        @DisplayName("deleteCollectedBefore()는 threshold보다 앞선 점을 지운다")
        fun deleteCollectedBefore() = runBlocking {
            val threshold = now.minus(Duration.ofDays(3))
            adapter.upsert(
                listOf(
                    article(newsId(1), StoryTestFixture.embedding(1f), storyA, collectedAt = threshold.minusMillis(1)),
                    article(newsId(2), StoryTestFixture.embedding(1f), storyA, collectedAt = threshold),
                    article(newsId(3), StoryTestFixture.embedding(1f), storyA, collectedAt = now)
                )
            )

            adapter.deleteCollectedBefore(threshold)

            assertEquals(setOf(newsId(2), newsId(3)), adapter.search(query(collectedAfter = Instant.EPOCH)).map { it.newsId }.toSet())
        }
    }

    @Nested
    @DisplayName("실패 분류")
    inner class Failure {

        @Test
        @DisplayName("없는 컬렉션을 부르면 INDEX_COLLECTION_MISSING이다")
        fun missingCollection() = runBlocking {
            val missing = QdrantCandidateIndexAdapter(client, QdrantCollection(client, "missing-" + UUID.randomUUID(), DIMENSION))

            val error = assertFailsWith<CandidateIndexException> { missing.search(query()) }

            assertEquals(CandidateIndexFailureCode.INDEX_COLLECTION_MISSING, error.failure.code)
            assertEquals("NOT_FOUND", error.failure.grpcStatus)
        }

        @Test
        @DisplayName("차원이 다른 벡터를 넣으면 INDEX_REQUEST_REJECTED다")
        fun wrongDimension() = runBlocking {
            val narrow = QdrantCollection(client, "narrow-" + UUID.randomUUID(), 8)
            narrow.ensure()
            val wide = QdrantCandidateIndexAdapter(client, narrow)

            val error = assertFailsWith<CandidateIndexException> {
                wide.upsert(listOf(article(newsId(1), StoryTestFixture.embedding(1f), storyA)))
            }

            assertEquals(CandidateIndexFailureCode.INDEX_REQUEST_REJECTED, error.failure.code)
        }
    }

    private fun query(
        collectedAfter: Instant = now.minus(Duration.ofDays(3)),
        limit: Int = 10,
        language: ArticleLanguage? = null
    ): CandidateQuery {
        return CandidateQuery(embedding = StoryTestFixture.embedding(1f), collectedAfter = collectedAfter, limit = limit, language = language)
    }

    private fun article(
        newsId: NewsId,
        embedding: Embedding,
        storyId: StoryId,
        collectedAt: Instant = now,
        language: String = "ko"
    ): IndexedArticle {
        return IndexedArticle(
            newsId = newsId,
            embedding = embedding,
            storyId = storyId,
            collectedAt = collectedAt,
            language = ArticleLanguage.of(language)
        )
    }

    private fun newsId(n: Int): NewsId = NewsId.of(UUID.fromString("018f0000-0000-7000-8000-%012d".format(n)))

    companion object {
        private const val DIMENSION = 1024
        private val container = QdrantContainer("qdrant/qdrant:v1.19.1").also { it.start() }
        private val client = QdrantClient(
            QdrantGrpcClient.newBuilder(container.host, container.grpcPort, false)
                .withTimeout(Duration.ofSeconds(10))
                .build()
        )

        @JvmStatic
        @AfterAll
        fun closeClient() {
            client.close()
        }
    }
}
