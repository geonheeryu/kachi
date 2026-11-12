package me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * notification-service MongoDB 저장 모델.
 */
@Document("notifications")
@CompoundIndexes(
    CompoundIndex(
        name = "idx_notifications_status_updated_at",
        def = "{'status': 1, 'updatedAt': 1}"
    ),
    CompoundIndex(
        name = "idx_notifications_stuck_processing",
        def = "{'status': 1, 'claimedAt': 1}"
    )
)
data class NotificationDocument(
    @Id
    val id: String,
    @Indexed(unique = true, name = "ux_notifications_request_id")
    val requestId: String,
    val requester: String,
    val channel: String,
    val recipientId: String,
    val message: String?,
    val summaryId: String?,
    val storyId: String?,
    val version: Long?,
    val keywords: List<String>,
    val userId: String?,
    val requestedAt: Instant,
    val status: String,
    val failureReason: String?,
    val updatedAt: Instant,
    val lastTransitionAt: Instant,
    val dispatchAttempts: Int,
    val claimedAt: Instant?,
    val claimedBy: String?,
)
