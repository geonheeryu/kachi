package me.rgunny.kachi.notification.application.port.outbound.outbox

import me.rgunny.kachi.notification.application.port.outbound.outbox.model.NotificationDispatchMessage

/**
 * 알림 이벤트 직렬화 port.
 *
 * core는 dispatch 메시지 모델만 만들고, JSON/Avro 같은 실제 직렬화 방식은 adapter가 담당한다.
 */
interface NotificationEventSerializer {

    fun serializeDispatch(message: NotificationDispatchMessage): String
}
