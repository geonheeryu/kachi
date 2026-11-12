package me.rgunny.kachi.notification.routing.fake

import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.NotificationRequestPublisherPort
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model.NotificationRequest

/**
 * 발행한 요청을 기록하는 publisher.
 *
 * failAt번째(0부터) 발행에서 예외를 던져 중간 실패를 흉내 낸다.
 * onPublish는 발행을 기록한 뒤 불리며, 발행 도중 다른 인스턴스가 저장소를 바꾸는 상황을 만든다.
 */
class FakeNotificationRequestPublisherPort : NotificationRequestPublisherPort {
    val published = mutableListOf<NotificationRequest>()
    var failAt: Int? = null
    var onPublish: ((NotificationRequest) -> Unit)? = null

    override suspend fun publish(request: NotificationRequest) {
        if (failAt == published.size) {
            throw IllegalStateException("publish failed. requestId=${request.requestId}")
        }
        published += request
        onPublish?.invoke(request)
    }
}
