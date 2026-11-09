package me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job

import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RoutingJobDocumentMapper")
class RoutingJobDocumentMapperTest {

    private val mapper = RoutingJobDocumentMapper()

    @Test
    @DisplayName("RoutingJob과 document를 상호 변환할 때 상태와 카운트를 보존한다")
    fun roundTrip() {
        val job = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", NOW)
            .complete(3, 3, NOW.plusSeconds(5))

        val document = mapper.toDocument(job)
        val restored = mapper.toDomain(document)

        assertEquals(job.id, restored.id)
        assertEquals("summary-1", restored.eventKey)
        assertEquals(RoutingJobKind.SUMMARY, restored.kind)
        assertEquals("tesla", restored.keyword)
        assertEquals(RoutingJobStatus.COMPLETED, restored.status)
        assertNull(restored.cursor)
        assertEquals(3, restored.targetCount)
        assertEquals(3, restored.publishedCount)
        assertEquals(NOW, restored.createdAt)
        assertEquals(NOW.plusSeconds(5), restored.updatedAt)
        assertEquals(NOW.plusSeconds(5), restored.completedAt)
    }

    @Test
    @DisplayName("STARTED job의 cursor를 보존한다")
    fun roundTripCursor() {
        val job = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", NOW)
            .advance("cursor-1", targetDelta = 2, publishedDelta = 2, now = NOW.plusSeconds(1))

        val restored = mapper.toDomain(mapper.toDocument(job))

        assertEquals(RoutingJobStatus.STARTED, restored.status)
        assertEquals("cursor-1", restored.cursor)
        assertEquals(2, restored.targetCount)
        assertEquals(2, restored.publishedCount)
    }
}
