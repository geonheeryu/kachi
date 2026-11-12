package me.rgunny.kachi.notification.routing.adapter.outbound.messaging

import kotlinx.coroutines.future.await
import me.rgunny.kachi.notification.contract.NotificationRequestedEvent
import me.rgunny.kachi.notification.contract.NotificationRequestedOrigin
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.NotificationRequestPublisherPort
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model.NotificationRequest
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.json.JsonMapper

/**
 * 알림 요청을 `NotificationRequestedEvent`로 직렬화해 접수 topic에 발행한다.
 *
 * key는 requestId다. 발행 성공의 근거는 broker ack이며 어댑터 자체 timeout은 두지 않는다.
 * producer의 delivery timeout이 future를 닫는다.
 */
class KafkaNotificationRequestPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper,
    private val topic: String,
) : NotificationRequestPublisherPort {

    init {
        require(topic.isNotBlank()) { "topic must not be blank" }
    }

    override suspend fun publish(request: NotificationRequest) {
        val payload = jsonMapper.writeValueAsString(toEvent(request))
        kafkaTemplate.send(topic, request.requestId, payload).await()
    }

    private fun toEvent(request: NotificationRequest): NotificationRequestedEvent {
        return NotificationRequestedEvent(
            requestId = request.requestId,
            requester = request.requester,
            channel = request.channel,
            recipientId = request.recipientId,
            message = request.message,
            origin = NotificationRequestedOrigin(
                summaryId = request.origin.summaryId,
                storyId = request.origin.storyId,
                version = request.origin.version,
                keywords = request.origin.keywords,
                userId = request.origin.userId,
            ),
        )
    }
}
