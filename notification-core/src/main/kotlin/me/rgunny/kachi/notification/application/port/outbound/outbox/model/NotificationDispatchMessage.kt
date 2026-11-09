package me.rgunny.kachi.notification.application.port.outbound.outbox.model

import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationId

/**
 * notification-service가 worker에 발행하는 발송 실행 메시지.
 *
 * adapter는 이 모델을 JSON, Avro 등 실제 broker payload로 직렬화한다.
 */
data class NotificationDispatchMessage(
    val notificationId: NotificationId,
    val requestId: String,
    val channel: NotificationChannel,
    val recipientId: String,
    val message: String,
)
