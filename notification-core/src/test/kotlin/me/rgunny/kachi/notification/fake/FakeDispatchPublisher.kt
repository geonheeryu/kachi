package me.rgunny.kachi.notification.fake

import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationDispatchPublisher
import me.rgunny.kachi.notification.domain.NotificationOutbox

class FakeDispatchPublisher(
    private val failure: RuntimeException? = null,
    /**
     * 발행이 진행되는 동안 저장소 상태가 바뀌는 상황을 만들기 위한 hook.
     */
    private val onPublish: (NotificationOutbox) -> Unit = {},
) : NotificationDispatchPublisher {
    var publishCount = 0

    override suspend fun publish(outbox: NotificationOutbox) {
        publishCount += 1
        onPublish(outbox)
        failure?.let { throw it }
    }
}
