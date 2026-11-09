package me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job.RoutingJobDocument
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobConflictException
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobTransitionConflictException
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query

@DisplayName("RoutingJobMongoPersistenceAdapter 통합 테스트")
class RoutingJobMongoPersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var adapter: RoutingJobMongoPersistenceAdapter

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @BeforeEach
    fun cleanUp() {
        mongoTemplate.remove(Query(), RoutingJobDocument::class.java).block()
    }

    @Test
    @DisplayName("insert한 job을 eventKey로 찾는다")
    fun insertAndFind() = runBlocking {
        val job = RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW)

        adapter.insert(job)

        val found = adapter.findByEventKey("story-1:1")
        assertNotNull(found)
        assertEquals(job.id, found.id)
        assertEquals(RoutingJobStatus.STARTED, found.status)
        assertNull(adapter.findByEventKey("story-1:2"))
    }

    @Test
    @DisplayName("같은 eventKey를 다시 insert하면 RoutingJobConflictException이다")
    fun rejectDuplicateEventKey() = runBlocking {
        adapter.insert(RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW))

        val exception = assertFailsWith<RoutingJobConflictException> {
            adapter.insert(RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW.plusSeconds(1)))
        }

        assertEquals("story-1:1", exception.eventKey)
        assertEquals(1L, mongoTemplate.count(Query(), RoutingJobDocument::class.java).block())
    }

    @Test
    @DisplayName("cursor가 null인 STARTED에서의 첫 advance가 저장된다")
    fun saveFirstAdvance() = runBlocking {
        val job = adapter.insert(RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW))

        val saved = adapter.saveTransition(job, job.advance("cursor-1", 3, 3, NOW.plusSeconds(1)))

        assertEquals("cursor-1", saved.cursor)
        val found = adapter.findByEventKey("story-1:1")!!
        assertEquals(RoutingJobStatus.STARTED, found.status)
        assertEquals("cursor-1", found.cursor)
        assertEquals(3, found.targetCount)
        assertEquals(3, found.publishedCount)
        assertEquals(NOW.plusSeconds(1), found.updatedAt)
        assertNull(found.completedAt)
    }

    @Test
    @DisplayName("전이 저장이 cursor와 카운트를 반영한다")
    fun saveTransitions() = runBlocking {
        val job = adapter.insert(RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW))
        val first = adapter.saveTransition(job, job.advance("cursor-1", 3, 3, NOW.plusSeconds(1)))
        val second = adapter.saveTransition(first, first.advance("cursor-2", 2, 1, NOW.plusSeconds(2)))

        adapter.saveTransition(second, second.complete(1, 1, NOW.plusSeconds(5)))

        val found = adapter.findByEventKey("story-1:1")!!
        assertEquals(RoutingJobStatus.COMPLETED, found.status)
        assertNull(found.cursor)
        assertEquals(6, found.targetCount)
        assertEquals(5, found.publishedCount)
        assertEquals(NOW.plusSeconds(5), found.updatedAt)
        assertEquals(NOW.plusSeconds(5), found.completedAt)
        assertEquals(NOW, found.createdAt)
    }

    @Test
    @DisplayName("묵은 cursor를 기대하는 advance 저장은 예외이고 문서가 바뀌지 않는다")
    fun rejectStaleCursor() = runBlocking {
        val job = adapter.insert(RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW))
        val first = adapter.saveTransition(job, job.advance("cursor-1", 3, 3, NOW.plusSeconds(1)))
        adapter.saveTransition(first, first.advance("cursor-2", 2, 2, NOW.plusSeconds(2)))

        val exception = assertFailsWith<RoutingJobTransitionConflictException> {
            adapter.saveTransition(first, first.advance("cursor-2-late", 4, 4, NOW.plusSeconds(3)))
        }

        assertEquals("story-1:1", exception.eventKey)
        assertEquals("cursor-1", exception.expectedCursor)
        val found = adapter.findByEventKey("story-1:1")!!
        assertEquals(RoutingJobStatus.STARTED, found.status)
        assertEquals("cursor-2", found.cursor)
        assertEquals(5, found.targetCount)
        assertEquals(5, found.publishedCount)
        assertEquals(NOW.plusSeconds(2), found.updatedAt)
    }

    @Test
    @DisplayName("COMPLETED 뒤의 늦은 advance 저장은 예외이고 문서가 COMPLETED로 남는다")
    fun rejectAdvanceAfterCompleted() = runBlocking {
        val job = adapter.insert(RoutingJob.start("story-1:1", RoutingJobKind.SUMMARY, "story-1", NOW))
        adapter.saveTransition(job, job.complete(3, 3, NOW.plusSeconds(5)))

        assertFailsWith<RoutingJobTransitionConflictException> {
            adapter.saveTransition(job, job.advance("cursor-1", 1, 1, NOW.plusSeconds(6)))
        }

        val found = adapter.findByEventKey("story-1:1")!!
        assertEquals(RoutingJobStatus.COMPLETED, found.status)
        assertNull(found.cursor)
        assertEquals(3, found.targetCount)
        assertEquals(3, found.publishedCount)
        assertEquals(NOW.plusSeconds(5), found.updatedAt)
        assertEquals(NOW.plusSeconds(5), found.completedAt)
    }
}
