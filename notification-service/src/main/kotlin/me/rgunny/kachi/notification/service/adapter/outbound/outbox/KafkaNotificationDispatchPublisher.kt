package me.rgunny.kachi.notification.service.adapter.outbound.outbox

import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationDispatchPublisher
import me.rgunny.kachi.notification.domain.NotificationOutbox
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * notification.dispatch Kafka publisher adapter.
 *
 * outbox에는 이미 직렬화된 eventPayload가 저장되어 있으므로 여기서는 payload를 재처리하지 않는다.
 * broker ack까지 완료되어야 application service가 outbox를 PUBLISHED로 전이할 수 있다.
 */
@Component
class KafkaNotificationDispatchPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : NotificationDispatchPublisher {

    override suspend fun publish(outbox: NotificationOutbox) {
        require(outbox.topic.isNotBlank()) { "topic must not be blank" }
        require(outbox.partitionKey.isNotBlank()) { "partitionKey must not be blank" }
        require(outbox.eventPayload.isNotBlank()) { "eventPayload must not be blank" }

        kafkaTemplate.send(
            outbox.topic,
            outbox.partitionKey,
            outbox.eventPayload,
        ).await()
    }
}

private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        whenComplete { result, failure ->
            if (failure != null) {
                continuation.resumeWithException(failure)
            } else {
                continuation.resume(result)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
}
