package me.rgunny.kachi.notification.worker.adapter.outbound.persistence.notification

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Notification 상태 전이 이력 document.
 *
 * 별도 collection에 append-only로 저장한다.
 * 상태 변경과 history insert는 같은 MongoDB transaction 안에서 확정한다.
 */
@Document("notification_histories")
@CompoundIndexes(
    CompoundIndex(
        name = "idx_notification_histories_notification_id_created_at",
        def = "{'notificationId': 1, 'createdAt': 1}"
    )
)
data class NotificationHistoryDocument(
    @Id
    val id: String,
    val notificationId: String,
    val fromStatus: String,
    val toStatus: String,
    val reason: String?,
    val createdAt: Instant,
)
