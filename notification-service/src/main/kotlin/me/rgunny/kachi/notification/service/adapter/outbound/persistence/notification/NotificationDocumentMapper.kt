package me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification

import java.util.UUID
import me.rgunny.kachi.notification.domain.Notification
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationHistory
import me.rgunny.kachi.notification.domain.NotificationHistoryId
import me.rgunny.kachi.notification.domain.NotificationId
import me.rgunny.kachi.notification.domain.NotificationOrigin
import me.rgunny.kachi.notification.domain.NotificationStatus
import me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification.NotificationDocument
import me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification.NotificationHistoryDocument
import org.springframework.stereotype.Component

/**
 * Notification domain <-> Mongo document 매퍼.
 */
@Component
class NotificationDocumentMapper {

    fun toDocument(notification: Notification): NotificationDocument {
        return NotificationDocument(
            id = notification.id.id.toString(),
            requestId = notification.requestId,
            requester = notification.requester,
            channel = notification.channel.name,
            recipientId = notification.recipientId,
            message = notification.message,
            summaryId = notification.origin.summaryId,
            storyId = notification.origin.storyId,
            version = notification.origin.version,
            keywords = notification.origin.keywords,
            userId = notification.origin.userId,
            requestedAt = notification.requestedAt,
            status = notification.status.name,
            failureReason = notification.failureReason,
            updatedAt = notification.updatedAt,
            lastTransitionAt = notification.lastTransitionAt,
            dispatchAttempts = notification.dispatchAttempts,
            claimedAt = notification.claimedAt,
            claimedBy = notification.claimedBy,
        )
    }

    fun toDomain(document: NotificationDocument): Notification {
        return Notification.restore(
            id = NotificationId.of(UUID.fromString(document.id)),
            requestId = document.requestId,
            requester = document.requester,
            channel = NotificationChannel.valueOf(document.channel),
            recipientId = document.recipientId,
            message = document.message,
            origin = NotificationOrigin(
                summaryId = document.summaryId,
                storyId = document.storyId,
                version = document.version,
                keywords = document.keywords,
                userId = document.userId,
            ),
            requestedAt = document.requestedAt,
            status = NotificationStatus.valueOf(document.status),
            failureReason = document.failureReason,
            updatedAt = document.updatedAt,
            lastTransitionAt = document.lastTransitionAt,
            dispatchAttempts = document.dispatchAttempts,
            claimedAt = document.claimedAt,
            claimedBy = document.claimedBy,
        )
    }

    fun toHistoryDocument(history: NotificationHistory): NotificationHistoryDocument {
        return NotificationHistoryDocument(
            id = history.id.id.toString(),
            notificationId = history.notificationId.id.toString(),
            fromStatus = history.fromStatus.name,
            toStatus = history.toStatus.name,
            reason = history.reason,
            createdAt = history.createdAt,
        )
    }

    fun toHistoryDomain(document: NotificationHistoryDocument): NotificationHistory {
        return NotificationHistory.restore(
            id = NotificationHistoryId.of(UUID.fromString(document.id)),
            notificationId = NotificationId.of(UUID.fromString(document.notificationId)),
            fromStatus = NotificationStatus.valueOf(document.fromStatus),
            toStatus = NotificationStatus.valueOf(document.toStatus),
            createdAt = document.createdAt,
            reason = document.reason,
        )
    }
}
