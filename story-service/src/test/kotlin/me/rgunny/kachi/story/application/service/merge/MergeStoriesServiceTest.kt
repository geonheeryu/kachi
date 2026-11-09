package me.rgunny.kachi.story.application.service.merge

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.exception.StoryOperationErrorCode
import me.rgunny.kachi.story.application.exception.StoryOperationException
import me.rgunny.kachi.story.application.port.inbound.merge.model.MergeStoryPairCommand
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.outbox.model.StoryMergedEvent
import me.rgunny.kachi.story.application.port.outbound.story.model.ReorganizeOutcome
import me.rgunny.kachi.story.application.service.assembly.AssemblyPolicy
import me.rgunny.kachi.story.domain.Embedding
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.Story
import me.rgunny.kachi.story.domain.StoryId
import me.rgunny.kachi.story.domain.StoryStatus
import me.rgunny.kachi.story.domain.outbox.StoryOutboxEventType
import me.rgunny.kachi.story.fake.FakeStoryOutboxEventSerializer
import me.rgunny.kachi.story.fake.InMemoryCandidateIndexPort
import me.rgunny.kachi.story.fake.InMemoryStoryStore
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.fixture.StoryTestFixture.article
import me.rgunny.kachi.story.fixture.StoryTestFixture.assemblyPolicy
import me.rgunny.kachi.story.fixture.StoryTestFixture.embedding
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 병합 규칙을 fake 포트로 검증한다.
 *
 * 기준 벡터는 (1, 0)이고 가까운 story의 벡터는 그것과의 코사인이 약 0.949다.
 */
@DisplayName("MergeStoriesService")
class MergeStoriesServiceTest {
    private val store = InMemoryStoryStore()
    private val index = InMemoryCandidateIndexPort()
    private val serializer = FakeStoryOutboxEventSerializer()

    /** 기준 벡터. */
    private val base: Embedding = embedding(1f, 0f)

    /** 코사인 약 0.949. θ_high 위. */
    private val near: Embedding = embedding(0.75f, 0.25f)

    /** 코사인 약 0.650. θ_high 아래. */
    private val far: Embedding = embedding(0.65f, 0.76f)

    @Test
    @DisplayName("가까운 두 OPEN story를 합치고 먼저 연 쪽이 살아남는다")
    fun mergeCloseStoriesIntoEarlierOpened() = runBlocking {
        val older = seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val newer = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))

        val result = service().mergeOpenStories()

        assertEquals(2, result.scannedCount)
        assertEquals(1, result.mergedCount)
        assertEquals(0, result.conflictedCount)

        val survivor = store.stories.getValue(older.id)
        assertEquals(StoryStatus.OPEN, survivor.status)
        assertEquals(2, survivor.articleCount)
        assertEquals(older.version + 1, survivor.version)

        val merged = store.stories.getValue(newer.id)
        assertEquals(StoryStatus.CLOSED, merged.status)
        assertEquals(older.id, merged.mergedInto)
        assertEquals(NOW, merged.closedAt)
        assertTrue(store.articles.values.all { it.storyId == older.id })
        assertTrue(index.points.values.all { it.storyId == older.id })
    }

    @Test
    @DisplayName("병합 outbox 행은 MERGED 이벤트이고 payload는 serializer 결과다")
    fun writeMergedOutboxRow() = runBlocking {
        val older = seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val newer = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))

        service().mergeOpenStories()

        val outbox = store.outboxes.single()
        assertEquals(StoryOutboxEventType.MERGED, outbox.eventType)
        assertEquals("${newer.id.value}>${older.id.value}", outbox.eventKey)
        assertEquals(older.id.value.toString(), outbox.partitionKey)
        val event = serializer.serialized.single() as StoryMergedEvent
        assertEquals(older.id.value, event.storyId)
        assertEquals(newer.id.value, event.mergedStoryId)
        assertEquals(NOW, event.mergedAt)
        assertEquals(FakeStoryOutboxEventSerializer.payloadOf(event), outbox.payload)
    }

    @Test
    @DisplayName("openedAt 동률이면 id 문자열이 작은 쪽이 살아남는다")
    fun tieBreakBySmallerIdString() = runBlocking {
        val openedAt = NOW.minus(Duration.ofHours(1))
        val smaller = seedStory(base, openedAt = openedAt, storyId = SMALLER_STORY_ID)
        val larger = seedStory(near, openedAt = openedAt, storyId = LARGER_STORY_ID)

        val result = service().mergeOpenStories()

        assertEquals(1, result.mergedCount)
        assertEquals(StoryStatus.OPEN, store.stories.getValue(smaller.id).status)
        assertEquals(smaller.id, store.stories.getValue(larger.id).mergedInto)
    }

    @Test
    @DisplayName("centroid 코사인이 정확히 θ_high인 쌍은 합친다")
    fun mergeAtExactThetaHigh() = runBlocking {
        seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        seedStory(embedding(1f, 1f), openedAt = NOW.minus(Duration.ofHours(1)))

        val result = service(assemblyPolicy(thetaHigh = 1.0 / sqrt(2.0))).mergeOpenStories()

        assertEquals(1, result.mergedCount)
    }

    @Test
    @DisplayName("centroid 코사인이 θ_high 아래인 쌍은 합치지 않는다")
    fun skipPairBelowThetaHigh() = runBlocking {
        seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        seedStory(far, openedAt = NOW.minus(Duration.ofHours(1)))

        val result = service().mergeOpenStories()

        assertEquals(0, result.mergedCount)
        assertTrue(store.stories.values.all { it.status == StoryStatus.OPEN })
        assertTrue(store.outboxes.isEmpty())
    }

    @Test
    @DisplayName("합산 기사 수가 상한을 넘는 쌍은 합치지 않는다")
    fun skipPairOverMaxArticles() = runBlocking {
        seedStory(base, base, openedAt = NOW.minus(Duration.ofHours(2)))
        seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))

        val result = service(assemblyPolicy(maxArticles = 2)).mergeOpenStories()

        assertEquals(0, result.mergedCount)
        assertTrue(store.stories.values.all { it.status == StoryStatus.OPEN })
    }

    @Test
    @DisplayName("CLOSED story는 후보에서 뺀다")
    fun skipClosedPartner() = runBlocking {
        seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val closed = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))
        store.stories[closed.id] = closed.close(NOW)

        val result = service().mergeOpenStories()

        assertEquals(0, result.mergedCount)
        assertNull(store.stories.getValue(closed.id).mergedInto)
    }

    @Test
    @DisplayName("합치는 사이 어느 한쪽이 바뀌면 그 쌍을 건너뛰고 다음 틱에 맡긴다")
    fun skipPairOnVersionConflict() = runBlocking {
        seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))
        store.mergeOutcomes += ReorganizeOutcome.STORY_CHANGED

        val result = service().mergeOpenStories()

        assertEquals(0, result.mergedCount)
        assertEquals(1, result.conflictedCount)
        assertTrue(store.stories.values.all { it.status == StoryStatus.OPEN })
        assertTrue(store.outboxes.isEmpty())
    }

    @Test
    @DisplayName("한 틱에서 흡수된 story는 다시 스캔하지 않고 생존자는 이어서 합쳐진다")
    fun chainMergesWithinOneTick() = runBlocking {
        val oldest = seedStory(base, openedAt = NOW.minus(Duration.ofHours(3)))
        val middle = seedStory(near, openedAt = NOW.minus(Duration.ofHours(2)))
        val newest = seedStory(base, openedAt = NOW.minus(Duration.ofHours(1)))

        val result = service().mergeOpenStories()

        assertEquals(3, result.scannedCount)
        assertEquals(2, result.mergedCount)
        val survivor = store.stories.getValue(oldest.id)
        assertEquals(StoryStatus.OPEN, survivor.status)
        assertEquals(3, survivor.articleCount)
        assertEquals(StoryStatus.CLOSED, store.stories.getValue(middle.id).status)
        assertEquals(StoryStatus.CLOSED, store.stories.getValue(newest.id).status)
        assertTrue(index.points.values.all { it.storyId == oldest.id })
        assertEquals(2, store.outboxes.size)
    }

    @Test
    @DisplayName("scan-window 밖에서 연 story는 스캔하지 않는다")
    fun skipStoriesOutsideScanWindow() = runBlocking {
        seedStory(base, openedAt = NOW.minus(Duration.ofHours(25)))
        seedStory(near, openedAt = NOW.minus(Duration.ofHours(26)))

        val result = service().mergeOpenStories()

        assertEquals(0, result.scannedCount)
        assertEquals(0, result.mergedCount)
    }

    @Test
    @DisplayName("색인 이전이 실패해도 병합은 유지되고 실패 수를 센다")
    fun keepMergeWhenIndexReassignFails() = runBlocking {
        val older = seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val newer = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))
        val service = MergeStoriesService(
            storyPersistencePort = store,
            candidateIndexPort = FailingReassignIndexPort(index),
            reorganizePersistencePort = store,
            eventSerializer = serializer,
            mergePolicy = mergePolicy(),
            assemblyPolicy = assemblyPolicy(),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.mergeOpenStories()

        assertEquals(1, result.mergedCount)
        assertEquals(1, result.indexReassignFailureCount)
        assertEquals(older.id, store.stories.getValue(newer.id).mergedInto)
    }

    @Test
    @DisplayName("운영자 병합은 θ 검사 없이 지정한 쪽이 살아남는다")
    fun mergePairIgnoresThetaAndSurvivorRule() = runBlocking {
        val older = seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val newer = seedStory(far, openedAt = NOW.minus(Duration.ofHours(1)))

        val result = service().mergeStoryPair(MergeStoryPairCommand(targetStoryId = newer.id, sourceStoryId = older.id))

        assertEquals(newer.id, result.survivor.id)
        assertEquals(older.id, result.mergedStoryId)
        assertEquals(2, result.survivor.articleCount)
        assertEquals(newer.id, store.stories.getValue(older.id).mergedInto)
        assertEquals(StoryOutboxEventType.MERGED, store.outboxes.single().eventType)
        assertTrue(index.points.values.all { it.storyId == newer.id })
    }

    @Test
    @DisplayName("운영자 병합은 없는 story와 닫힌 story를 거부한다")
    fun rejectPairOfMissingOrClosedStory() = runBlocking {
        val open = seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val closed = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))
        store.stories[closed.id] = closed.close(NOW)

        val notFound = assertFailsWith<StoryOperationException> {
            service().mergeStoryPair(MergeStoryPairCommand(targetStoryId = open.id, sourceStoryId = StoryId.newId()))
        }
        assertEquals(StoryOperationErrorCode.STORY_NOT_FOUND, notFound.errorCode)

        val notOpen = assertFailsWith<StoryOperationException> {
            service().mergeStoryPair(MergeStoryPairCommand(targetStoryId = open.id, sourceStoryId = closed.id))
        }
        assertEquals(StoryOperationErrorCode.STORY_NOT_OPEN, notOpen.errorCode)
    }

    @Test
    @DisplayName("운영자 병합은 같은 story와 상한 초과 쌍을 거부한다")
    fun rejectIncompatiblePair() = runBlocking {
        val target = seedStory(base, base, openedAt = NOW.minus(Duration.ofHours(2)))
        val source = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))

        val self = assertFailsWith<StoryOperationException> {
            service().mergeStoryPair(MergeStoryPairCommand(targetStoryId = target.id, sourceStoryId = target.id))
        }
        assertEquals(StoryOperationErrorCode.MERGE_INCOMPATIBLE, self.errorCode)

        val overMax = assertFailsWith<StoryOperationException> {
            service(assemblyPolicy(maxArticles = 2))
                .mergeStoryPair(MergeStoryPairCommand(targetStoryId = target.id, sourceStoryId = source.id))
        }
        assertEquals(StoryOperationErrorCode.MERGE_INCOMPATIBLE, overMax.errorCode)
        assertTrue(store.outboxes.isEmpty())
    }

    @Test
    @DisplayName("운영자 병합 중 story가 바뀌면 실패로 알린다")
    fun reportConflictOnPairVersionChange() = runBlocking {
        val target = seedStory(base, openedAt = NOW.minus(Duration.ofHours(2)))
        val source = seedStory(near, openedAt = NOW.minus(Duration.ofHours(1)))
        store.mergeOutcomes += ReorganizeOutcome.STORY_CHANGED

        val conflict = assertFailsWith<StoryOperationException> {
            service().mergeStoryPair(MergeStoryPairCommand(targetStoryId = target.id, sourceStoryId = source.id))
        }

        assertEquals(StoryOperationErrorCode.REORGANIZE_CONFLICT, conflict.errorCode)
        assertTrue(store.stories.values.all { it.status == StoryStatus.OPEN })
        assertTrue(store.outboxes.isEmpty())
    }

    private fun service(policy: AssemblyPolicy = assemblyPolicy()): MergeStoriesService {
        return MergeStoriesService(
            storyPersistencePort = store,
            candidateIndexPort = index,
            reorganizePersistencePort = store,
            eventSerializer = serializer,
            mergePolicy = mergePolicy(),
            assemblyPolicy = policy,
            clock = StoryTestFixture.CLOCK
        )
    }

    private fun mergePolicy(): StoryMergePolicy {
        return StoryMergePolicy(scanWindow = Duration.ofHours(24), scanLimit = 200)
    }

    /**
     * 주어진 벡터의 기사들로 story 하나를 저장소와 색인에 심는다.
     */
    private suspend fun seedStory(
        vararg embeddings: Embedding,
        openedAt: Instant,
        storyId: StoryId = StoryId.newId()
    ): Story {
        val articles = embeddings.mapIndexed { i, embedding ->
            article(
                newsId = NewsId.of(UUID.randomUUID()),
                title = "후보 기사 ${storyId.value.toString().takeLast(4)}-$i",
                embedding = embedding,
                storyId = storyId,
                collectedAt = openedAt
            )
        }
        val story = articles.drop(1).fold(Story.open(articles.first(), openedAt)) { acc, article -> acc.attach(article, openedAt) }
        store.seed(story, *articles.toTypedArray())
        index.upsert(articles.map { IndexedArticle.from(it) })

        return story
    }

    /** story 단위 이전이 항상 실패하는 색인. */
    private class FailingReassignIndexPort(
        private val delegate: InMemoryCandidateIndexPort
    ) : CandidateIndexPort by delegate {

        override suspend fun reassignStory(from: StoryId, to: StoryId) {
            throw IllegalStateException("index unavailable")
        }
    }

    private companion object {
        val SMALLER_STORY_ID: StoryId = StoryId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000c1"))
        val LARGER_STORY_ID: StoryId = StoryId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000c2"))
    }
}
