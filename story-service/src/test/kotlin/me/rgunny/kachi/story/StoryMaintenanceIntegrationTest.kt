package me.rgunny.kachi.story

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryArticleMongoDocument
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryArticlePersistenceAdapter
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryAssemblyPersistenceAdapter
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryMongoDocument
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryPersistenceAdapter
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryReorganizePersistenceAdapter
import me.rgunny.kachi.story.adapter.outbound.candidateindex.QdrantCandidateIndexAdapter
import me.rgunny.kachi.story.adapter.outbound.candidateindex.QdrantCollection
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateHit
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateQuery
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.service.cleanup.CleanupCandidateIndexService
import me.rgunny.kachi.story.application.service.close.CloseIdleStoriesService
import me.rgunny.kachi.story.application.service.close.StoryClosePolicy
import me.rgunny.kachi.story.application.service.index.RebuildCandidateIndexService
import me.rgunny.kachi.story.application.service.merge.MergeStoriesService
import me.rgunny.kachi.story.application.service.merge.StoryMergePolicy
import me.rgunny.kachi.story.domain.AutoMergedLinkDecision
import me.rgunny.kachi.story.domain.Embedding
import me.rgunny.kachi.story.domain.NewStoryLinkDecision
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryStatus
import me.rgunny.kachi.story.domain.outbox.StoryOutboxEventType
import me.rgunny.kachi.story.fake.FakeStoryOutboxEventSerializer
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.support.StoryOutboxCollection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.testcontainers.qdrant.QdrantContainer

/**
 * 주기 작업을 실제 Mongo·Qdrant로 잇는 통합 테스트.
 *
 * 닫기 뒤 벡터 삭제, 병합 뒤 payload 이전과 MERGED outbox 행, 정리 뒤 옛 점 삭제, 재구축 왕복을 본다.
 */
@DisplayName("story 유지보수 통합 테스트")
class StoryMaintenanceIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var storyAdapter: StoryPersistenceAdapter

    @Autowired
    private lateinit var articleAdapter: StoryArticlePersistenceAdapter

    @Autowired
    private lateinit var assemblyAdapter: StoryAssemblyPersistenceAdapter

    @Autowired
    private lateinit var reorganizeAdapter: StoryReorganizePersistenceAdapter

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @Autowired
    private lateinit var qdrantContainer: QdrantContainer

    private val serializer = FakeStoryOutboxEventSerializer()
    private val outboxCollection by lazy { StoryOutboxCollection(mongoTemplate) }

    private lateinit var client: QdrantClient
    private lateinit var indexAdapter: QdrantCandidateIndexAdapter

    @BeforeEach
    fun prepareIndex() = runBlocking {
        mongoTemplate.remove(StoryMongoDocument::class.java).all().block()
        mongoTemplate.remove(StoryArticleMongoDocument::class.java).all().block()
        outboxCollection.clear()

        client = QdrantClient(
            QdrantGrpcClient.newBuilder(qdrantContainer.host, qdrantContainer.grpcPort, false)
                .withTimeout(Duration.ofSeconds(10))
                .build()
        )
        val collection = QdrantCollection(client, "story-maintenance-" + UUID.randomUUID(), DIMENSION)
        collection.ensure()
        indexAdapter = QdrantCandidateIndexAdapter(client, collection)
    }

    @AfterEach
    fun closeClient() {
        client.close()
    }

    @Test
    @DisplayName("닫기는 story를 CLOSED로 바꾸고 그 벡터를 색인에서 지운다")
    fun closeIdleStoryAndDeleteVectors() = runBlocking {
        val idle = seedStory(StoryTestFixture.embedding(1f), openedAt = NOW.minus(Duration.ofHours(49)))
        val recent = seedStory(StoryTestFixture.embedding(0f, 1f), openedAt = NOW.minus(Duration.ofHours(1)))
        val service = CloseIdleStoriesService(
            storyPersistencePort = storyAdapter,
            candidateIndexPort = indexAdapter,
            policy = StoryClosePolicy(closeAfter = Duration.ofHours(48), batchLimit = 100),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.closeIdleStories()

        assertEquals(1, result.closedCount)
        assertEquals(StoryStatus.CLOSED, storyAdapter.findById(idle.id)?.status)
        assertEquals(StoryStatus.OPEN, storyAdapter.findById(recent.id)?.status)
        assertEquals(listOf(recent.id), searchAll().map { it.storyId })
    }

    @Test
    @DisplayName("병합은 기사와 색인 payload를 생존자로 옮기고 MERGED outbox 행을 남긴다")
    fun mergeMovesArticlesAndIndexPayload() = runBlocking {
        val older = seedStory(StoryTestFixture.embedding(1f, 0f), openedAt = NOW.minus(Duration.ofHours(2)))
        val newer = seedStory(StoryTestFixture.embedding(0.75f, 0.25f), openedAt = NOW.minus(Duration.ofHours(1)), autoMergedDecision = true)
        val service = MergeStoriesService(
            storyPersistencePort = storyAdapter,
            candidateIndexPort = indexAdapter,
            reorganizePersistencePort = reorganizeAdapter,
            eventSerializer = serializer,
            mergePolicy = StoryMergePolicy(scanWindow = Duration.ofHours(24), scanLimit = 200),
            assemblyPolicy = StoryTestFixture.assemblyPolicy(),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.mergeOpenStories()

        assertEquals(1, result.mergedCount)
        assertEquals(0, result.indexReassignFailureCount)
        val survivor = assertNotNull(storyAdapter.findById(older.id))
        assertEquals(2, survivor.articleCount)
        val merged = assertNotNull(storyAdapter.findById(newer.id))
        assertEquals(StoryStatus.CLOSED, merged.status)
        assertEquals(older.id, merged.mergedInto)
        assertEquals(2, articleAdapter.findByStory(older.id).size)
        assertTrue(searchAll().all { it.storyId == older.id })

        val mergedRows = outboxCollection.findAll().filter { it.eventType == StoryOutboxEventType.MERGED.name }
        assertEquals(listOf("${newer.id.value}>${older.id.value}"), mergedRows.map { it.eventKey })
    }

    @Test
    @DisplayName("재구축은 색인을 비우고 OPEN story의 기사만 다시 채운다")
    fun rebuildReindexesOpenStoriesOnly() = runBlocking {
        val open = seedStory(StoryTestFixture.embedding(1f), openedAt = NOW.minus(Duration.ofHours(1)))
        val closed = seedStory(StoryTestFixture.embedding(0f, 1f), openedAt = NOW.minus(Duration.ofHours(2)))
        storyAdapter.update(closed.close(NOW), closed.version)
        val service = RebuildCandidateIndexService(
            storyArticlePersistencePort = articleAdapter,
            storyPersistencePort = storyAdapter,
            candidateIndexPort = indexAdapter,
            assemblyPolicy = StoryTestFixture.assemblyPolicy(),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.rebuild()

        assertEquals(2, result.scannedCount)
        assertEquals(1, result.indexedCount)
        assertEquals(1, result.skippedClosedCount)
        assertEquals(listOf(open.id), searchAll().map { it.storyId })
    }

    @Test
    @DisplayName("정리는 보관 창을 지난 점만 색인에서 지운다")
    fun cleanupDeletesPointsOutsideWindow() = runBlocking {
        val old = indexedArticle(StoryTestFixture.embedding(1f), collectedAt = NOW.minus(Duration.ofHours(73)))
        val recent = indexedArticle(StoryTestFixture.embedding(1f), collectedAt = NOW)
        indexAdapter.upsert(listOf(old, recent))
        val service = CleanupCandidateIndexService(
            candidateIndexPort = indexAdapter,
            assemblyPolicy = StoryTestFixture.assemblyPolicy(),
            clock = StoryTestFixture.CLOCK
        )

        service.cleanup()

        assertEquals(listOf(recent.newsId), searchAll().map { it.newsId })
    }

    /**
     * 기사 하나로 story를 Mongo와 색인에 심는다.
     *
     * [autoMergedDecision]이면 merged 판정 기사로 만들어, 병합 뒤 소속이 갈라진 복원 경로도 함께 덮는다.
     */
    private suspend fun seedStory(embedding: Embedding, openedAt: Instant, autoMergedDecision: Boolean = false): Story {
        val storyId = StoryId.newId()
        val article: StoryArticle = StoryTestFixture.article(
            newsId = NewsId.of(UUID.randomUUID()),
            storyId = storyId,
            embedding = embedding,
            publishedAt = openedAt,
            collectedAt = openedAt,
            decision = if (autoMergedDecision) AutoMergedLinkDecision(storyId, 0.9) else NewStoryLinkDecision(null, null)
        )
        val story = Story.open(article, openedAt)
        val outbox = StoryTestFixture.outbox(
            eventKey = "${story.id.value}:${article.newsId.value}",
            partitionKey = story.id.value.toString()
        )
        assemblyAdapter.openStory(story, article, outbox)
        indexAdapter.upsert(listOf(IndexedArticle.from(article)))

        return story
    }

    private fun indexedArticle(embedding: Embedding, collectedAt: Instant): IndexedArticle {
        return IndexedArticle.from(
            StoryTestFixture.article(
                newsId = NewsId.of(UUID.randomUUID()),
                storyId = StoryId.newId(),
                embedding = embedding,
                collectedAt = collectedAt
            )
        )
    }

    private suspend fun searchAll(): List<CandidateHit> {
        return indexAdapter.search(
            CandidateQuery(embedding = StoryTestFixture.embedding(1f), collectedAfter = Instant.EPOCH, limit = 100)
        )
    }

    private companion object {
        const val DIMENSION = 1024
    }
}
