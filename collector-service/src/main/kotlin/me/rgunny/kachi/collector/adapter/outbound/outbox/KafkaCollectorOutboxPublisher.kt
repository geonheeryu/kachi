package me.rgunny.kachi.collector.adapter.outbound.outbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import me.rgunny.kachi.collector.application.exception.CollectorOutboxErrorCode
import me.rgunny.kachi.collector.application.exception.CollectorOutboxPublishException
import me.rgunny.kachi.collector.application.port.outbound.outbox.CollectorOutboxPublisherPort
import me.rgunny.kachi.collector.domain.outbox.CollectorOutbox
import me.rgunny.kachi.collector.domain.outbox.CollectorOutboxEventType
import org.springframework.kafka.core.KafkaTemplate

/**
 * outbox 행 하나를 Kafka 레코드 하나로 발행하는 어댑터.
 *
 * topic은 행의 eventType이 고르고, key는 partitionKey, value는 기록 시점의 계약 JSON을 그대로 보낸다.
 * payload를 읽거나 바꾸지 않는다. 계약대로 읽히는지는 소비자 쪽 검증의 몫이다.
 *
 * 발행 성공의 근거는 broker ack다. 어댑터 자체의 timeout은 두지 않는다. producer의 delivery timeout이 future를 닫는다.
 */
class KafkaCollectorOutboxPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val topics: Map<CollectorOutboxEventType, String>
) : CollectorOutboxPublisherPort {

    override suspend fun publish(outbox: CollectorOutbox) {
        val topic = topics.getValue(outbox.eventType)

        try {
            kafkaTemplate.send(topic, outbox.partitionKey, outbox.payload).await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw CollectorOutboxPublishException(
                errorCode = CollectorOutboxErrorCode.OUTBOX_PUBLISH_FAILED,
                retryable = KafkaPublishFailureClassifier.isRetryable(error),
                detail = "topic=$topic, eventKey=${outbox.eventKey}",
                cause = error
            )
        }
    }
}
