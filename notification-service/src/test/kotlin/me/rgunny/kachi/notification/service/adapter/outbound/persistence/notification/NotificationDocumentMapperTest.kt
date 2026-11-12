package me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import me.rgunny.kachi.notification.domain.Notification
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationOrigin
import me.rgunny.kachi.notification.domain.NotificationStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NotificationDocumentMapper")
class NotificationDocumentMapperTest {

    private val mapper = NotificationDocumentMapper()

    @Test
    @DisplayName("Notification aggregate와 document를 상호 변환할 때 상태 snapshot을 보존한다")
    fun roundTrip() {
        val origin = NotificationOrigin(
            summaryId = "summary-1",
            storyId = "story-1",
            version = 2,
            keywords = listOf("tesla", "nvidia"),
            userId = "user-1",
        )
        val requestedAt = Instant.parse("2026-06-16T00:00:00Z")
        val publishedAt = Instant.parse("2026-06-16T00:00:10Z")
        val notification = Notification.request(
            requestId = "request-1",
            requester = "collector-service",
            channel = NotificationChannel.SLACK,
            recipientId = "user-1",
            message = "hello",
            origin = origin,
            now = requestedAt,
        )
            .markPublished(publishedAt)

        val document = mapper.toDocument(notification)
        val restored = mapper.toDomain(document)

        assertEquals(notification.id, restored.id)
        assertEquals("request-1", restored.requestId)
        assertEquals("collector-service", restored.requester)
        assertEquals(NotificationChannel.SLACK, restored.channel)
        assertEquals("user-1", restored.recipientId)
        assertEquals("hello", restored.message)
        assertEquals("summary-1", document.summaryId)
        assertEquals("story-1", document.storyId)
        assertEquals(2L, document.version)
        assertEquals(listOf("tesla", "nvidia"), document.keywords)
        assertEquals("user-1", document.userId)
        assertEquals(origin, restored.origin)
        assertEquals(requestedAt, restored.requestedAt)
        assertEquals(NotificationStatus.PUBLISHED, restored.status)
        assertEquals(publishedAt, restored.updatedAt)
        assertEquals(publishedAt, restored.lastTransitionAt)
        assertEquals(0, restored.dispatchAttempts)

        val historyDocument = mapper.toHistoryDocument(notification.uncommittedHistories.single())
        assertEquals(notification.id.id.toString(), historyDocument.notificationId)
        assertEquals(NotificationStatus.REQUESTED.name, historyDocument.fromStatus)
        assertEquals(NotificationStatus.PUBLISHED.name, historyDocument.toStatus)
    }

    @Test
    @DisplayName("PROCESSING claim 정보를 document와 domain 사이에서 보존한다")
    fun roundTripProcessingClaim() {
        val requestedAt = Instant.parse("2026-06-16T00:00:00Z")
        val publishedAt = Instant.parse("2026-06-16T00:00:10Z")
        val claimedAt = Instant.parse("2026-06-16T00:00:20Z")
        val notification = Notification.request(
            requestId = "request-2",
            requester = "collector-service",
            channel = NotificationChannel.SLACK,
            recipientId = "user-1",
            message = "hello",
            origin = NotificationOrigin.NONE,
            now = requestedAt,
        )
            .markPublished(publishedAt)
            .markProcessing(claimedAt, "worker-1")

        val document = mapper.toDocument(notification)
        val restored = mapper.toDomain(document)

        assertEquals(claimedAt, document.claimedAt)
        assertEquals("worker-1", document.claimedBy)
        assertEquals(claimedAt, restored.claimedAt)
        assertEquals("worker-1", restored.claimedBy)
        assertEquals(NotificationOrigin.NONE, restored.origin)

        val sent = restored.markSent(Instant.parse("2026-06-16T00:00:30Z"))
        assertNull(sent.claimedAt)
        assertNull(sent.claimedBy)
    }
}
