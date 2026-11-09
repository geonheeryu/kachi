package me.rgunny.kachi.notification.routing.config

import me.rgunny.kachi.notification.routing.adapter.inbound.messaging.InvalidAiEventMessageException
import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * ai.* topic을 읽는 routing listener 전용 Kafka runtime 설정.
 *
 * 읽을 수 없는 payload나 지원하지 않는 schemaVersion은 다시 받아도 같으므로 즉시 DLT로 보낸다.
 * user-service·Mongo·접수 topic 발행 장애로 라우팅이 실패하면 짧게 재시도한 뒤 DLT로 보낸다.
 * 라우팅 도중 실패해도 RoutingJob이 STARTED로 남아 DLT 재발행 시 저장된 cursor부터 다시 발행되고, 접수 쪽 requestId 멱등이 중복을 거른다.
 */
@Configuration
class NotificationRoutingKafkaConfig {

    @Bean
    fun notificationRoutingKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        notificationRoutingErrorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        return ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            setCommonErrorHandler(notificationRoutingErrorHandler)
        }
    }

    /**
     * FixedBackOff의 maxAttempts는 재시도 횟수라서, 설정의 maxAttempts가 전체 처리 시도 횟수가 되도록 1을 뺀다.
     */
    @Bean
    fun notificationRoutingErrorHandler(
        kafkaTemplate: KafkaOperations<String, String>,
        properties: NotificationRoutingProperties,
    ): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            TopicPartition(properties.dlt.topic, record.partition())
        }
        val backOff = FixedBackOff(
            properties.retry.backoff.toMillis(),
            (properties.retry.maxAttempts - 1).coerceAtLeast(0),
        )

        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(InvalidAiEventMessageException::class.java)
        }
    }
}
