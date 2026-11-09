package me.rgunny.kachi.notification.routing.domain

import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@DisplayName("RoutingJob")
class RoutingJobTest {
    private val now = NOW

    @Test
    @DisplayName("start는 STARTED이고 cursor가 null이며 카운트가 0이다")
    fun start() {
        val job = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now)

        assertEquals(RoutingJobStatus.STARTED, job.status)
        assertNull(job.cursor)
        assertEquals(0, job.targetCount)
        assertEquals(0, job.publishedCount)
        assertEquals(now, job.createdAt)
        assertEquals(now, job.updatedAt)
        assertNull(job.completedAt)

        assertFailsWith<IllegalArgumentException> { RoutingJob.start(" ", RoutingJobKind.SUMMARY, "tesla", now) }
        assertFailsWith<IllegalArgumentException> { RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "", now) }
    }

    @Test
    @DisplayName("advance는 cursor를 바꾸고 누계에 delta를 더하며 원본은 그대로다")
    fun advance() {
        val job = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now)

        val first = job.advance(cursor = "cursor-1", targetDelta = 3, publishedDelta = 3, now = now.plusSeconds(1))
        val second = first.advance(cursor = "cursor-2", targetDelta = 0, publishedDelta = 0, now = now.plusSeconds(2))

        assertEquals(RoutingJobStatus.STARTED, first.status)
        assertEquals("cursor-1", first.cursor)
        assertEquals(3, first.targetCount)
        assertEquals(3, first.publishedCount)
        assertEquals(now.plusSeconds(1), first.updatedAt)
        assertNull(first.completedAt)
        assertEquals(job.id, first.id)
        assertEquals(now, first.createdAt)

        assertEquals("cursor-2", second.cursor)
        assertEquals(3, second.targetCount)
        assertEquals(3, second.publishedCount)

        assertNull(job.cursor)
        assertEquals(0, job.targetCount)
        assertEquals(now, job.updatedAt)
        assertEquals("cursor-1", first.cursor)
    }

    @Test
    @DisplayName("complete는 마지막 delta를 더하고 cursor를 null로 하며 원본은 그대로다")
    fun complete() {
        val completedAt = now.plusSeconds(5)
        val advanced = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now)
            .advance("cursor-1", targetDelta = 3, publishedDelta = 3, now = now.plusSeconds(1))

        val completed = advanced.complete(targetDelta = 2, publishedDelta = 1, now = completedAt)

        assertEquals(RoutingJobStatus.COMPLETED, completed.status)
        assertNull(completed.cursor)
        assertEquals(5, completed.targetCount)
        assertEquals(4, completed.publishedCount)
        assertEquals(completedAt, completed.updatedAt)
        assertEquals(completedAt, completed.completedAt)
        assertEquals(advanced.id, completed.id)
        assertEquals(RoutingJobStatus.STARTED, advanced.status)
        assertEquals("cursor-1", advanced.cursor)
        assertEquals(3, advanced.targetCount)
    }

    @Test
    @DisplayName("COMPLETED는 advance도 complete도 할 수 없다")
    fun rejectTransitionAfterCompleted() {
        val completed = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now)
            .complete(1, 1, now.plusSeconds(1))

        assertFailsWith<IllegalStateException> { completed.complete(1, 1, now.plusSeconds(2)) }
        assertFailsWith<IllegalStateException> { completed.advance("cursor-1", 1, 1, now.plusSeconds(2)) }
    }

    @ParameterizedTest
    @CsvSource("-1, 0", "1, -1", "2, 3")
    @DisplayName("delta가 음수이거나 발행 delta가 대상 delta를 넘으면 advance도 complete도 할 수 없다")
    fun rejectInvalidDeltas(targetDelta: Int, publishedDelta: Int) {
        val job = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now)

        assertFailsWith<IllegalArgumentException> {
            job.advance("cursor-1", targetDelta, publishedDelta, now.plusSeconds(1))
        }
        assertFailsWith<IllegalArgumentException> {
            job.complete(targetDelta, publishedDelta, now.plusSeconds(1))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    @DisplayName("advance의 cursor는 blank일 수 없다")
    fun rejectBlankCursor(cursor: String) {
        val job = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now)

        assertFailsWith<IllegalArgumentException> { job.advance(cursor, 1, 1, now.plusSeconds(1)) }
    }

    @Test
    @DisplayName("restore는 COMPLETED와 completedAt의 짝을 검사한다")
    fun restoreChecksCompletedAt() {
        assertFailsWith<IllegalArgumentException> { restore(RoutingJobStatus.COMPLETED, cursor = null, completedAt = null) }
        assertFailsWith<IllegalArgumentException> { restore(RoutingJobStatus.STARTED, cursor = null, completedAt = now) }
        assertEquals(RoutingJobStatus.COMPLETED, restore(RoutingJobStatus.COMPLETED, cursor = null, completedAt = now).status)
    }

    @Test
    @DisplayName("restore는 COMPLETED의 cursor가 null이고 cursor가 blank가 아닌지 검사한다")
    fun restoreChecksCursor() {
        assertFailsWith<IllegalArgumentException> { restore(RoutingJobStatus.COMPLETED, cursor = "cursor-1", completedAt = now) }
        assertFailsWith<IllegalArgumentException> { restore(RoutingJobStatus.STARTED, cursor = " ", completedAt = null) }
        assertEquals("cursor-1", restore(RoutingJobStatus.STARTED, cursor = "cursor-1", completedAt = null).cursor)
    }

    private fun restore(status: RoutingJobStatus, cursor: String?, completedAt: Instant?): RoutingJob {
        return RoutingJob.restore(
            id = RoutingJobId.newId(),
            eventKey = "summary-1",
            kind = RoutingJobKind.SUMMARY,
            keyword = "tesla",
            status = status,
            cursor = cursor,
            targetCount = 0,
            publishedCount = 0,
            createdAt = now,
            updatedAt = now,
            completedAt = completedAt,
        )
    }
}
