package me.rgunny.kachi.notification.application.service

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.notification.application.port.inbound.request.RequestNotificationUseCase
import me.rgunny.kachi.notification.application.port.inbound.request.model.RequestNotificationCommand
import me.rgunny.kachi.notification.application.port.inbound.request.model.RequestNotificationResult
import me.rgunny.kachi.notification.application.port.outbound.idempotency.NotificationDeduplicationPort
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationEventSerializer
import me.rgunny.kachi.notification.application.port.outbound.outbox.model.NotificationDispatchMessage
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationRequestPersistencePort
import me.rgunny.kachi.notification.domain.Notification
import me.rgunny.kachi.notification.domain.NotificationOutbox

/**
 * 외부 알림 요청 접수 application service.
 */
class RequestNotificationService(
    private val notificationPersistencePort: NotificationPersistencePort,
    private val requestPersistencePort: NotificationRequestPersistencePort,
    private val deduplicationPort: NotificationDeduplicationPort,
    private val eventSerializer: NotificationEventSerializer,
    private val policy: RequestNotificationPolicy,
    private val clock: Clock,
) : RequestNotificationUseCase {

    override suspend fun request(command: RequestNotificationCommand): RequestNotificationResult {
        // 1. requestId를 멱등키로 사용해 중복 요청을 판별한다.
        val dedupeKey = requestDedupeKey(command.requestId)

        // 2. 멱등 마커를 선점해 같은 요청의 동시 처리를 먼저 걸러낸다.
        val isDuplicateDispatch = !deduplicationPort.acquire(dedupeKey, policy.dedupeTtl)

        // 3. 중복요청일 경우, 이미 접수된 알림을 찾아 같은 결과로 응답한다.
        if (isDuplicateDispatch) {
            val notification = notificationPersistencePort.findByRequestId(command.requestId)
                ?: throw IllegalStateException("dedupe marker exists but notification not found. requestId=${command.requestId}")
            return RequestNotificationResult(
                notificationId = notification.id,
                status = notification.status,
                duplicated = true,
                acceptedAt = notification.acceptedAt(),
            )
        }

        try {
            val now = Instant.now(clock)

            // 4. 신규 요청은 REQUESTED 상태로 접수한다.
            val notification = Notification.request(
                requestId = command.requestId,
                requester = command.requester,
                channel = command.channel,
                recipientId = command.recipientId,
                message = command.message,
                origin = command.origin,
                now = now,
            )

            // 5. worker에 넘길 dispatch 메시지를 outbox payload로 고정한다.
            val dispatchMessage = NotificationDispatchMessage(
                notificationId = notification.id,
                requestId = notification.requestId,
                channel = notification.channel,
                recipientId = notification.recipientId,
                message = notification.message.orEmpty(),
            )
            val dispatchPayload = eventSerializer.serializeDispatch(dispatchMessage)
            val outbox = NotificationOutbox.create(
                notificationId = notification.id,
                topic = policy.dispatchTopic,
                partitionKey = command.recipientId,
                eventPayload = dispatchPayload,
                now = now,
            )

            // 6. 알림 접수와 dispatch 발행 대기열은 같은 DB transaction 경계에서 확정한다.
            // Redis dedupe는 MongoDB rollback 대상이 아니므로, 저장 실패 시 catch 블록에서 별도로 해제한다.
            val savedNotification = requestPersistencePort.saveRequested(notification, outbox)

            // 7. 인입 채널과 무관하게 동일한 접수 결과를 반환한다.
            return RequestNotificationResult(
                notificationId = savedNotification.id,
                status = savedNotification.status,
                duplicated = false,
                acceptedAt = savedNotification.acceptedAt(),
            )
        } catch (e: Exception) {
            // 8. 접수 저장에 실패했다면 멱등 마커를 해제해 재시도를 허용한다.
            deduplicationPort.release(dedupeKey)
            throw e
        }
    }

    private fun Notification.acceptedAt(): Instant {
        return requestedAt
    }

    private fun requestDedupeKey(requestId: String): String {
        return "notification:request:$requestId"
    }
}
