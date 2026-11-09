package me.rgunny.kachi.notification.application.port.outbound.outbox

import me.rgunny.kachi.notification.domain.NotificationOutbox

/**
 * worker 발송 실행 이벤트 발행 port.
 *
 * notification-service는 outbox payload를 `notification.dispatch` topic으로 발행한다.
 * 발행 실패는 예외로 표현하고, application service가 outbox retry 상태로 반영한다.
 */
interface NotificationDispatchPublisher {

    suspend fun publish(outbox: NotificationOutbox)
}
