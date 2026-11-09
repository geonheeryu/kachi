package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.adapter.outbound.outbox.KafkaAiOutboxPublisher
import me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxPublisherPort
import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate

/**
 * 이벤트 발행이 켜져 있을 때만 Kafka 발행 어댑터를 조립한다.
 *
 * 꺼진 인스턴스에는 발행 포트 구현이 없다.
 * relay가 켜져 있다면 그 사실을 relay 조립이 기동 실패로 알린다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = AiEventsProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true"
)
class AiEventsConfig {

    @Bean
    fun kafkaAiOutboxPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        properties: AiEventsProperties
    ): AiOutboxPublisherPort {
        return KafkaAiOutboxPublisher(
            kafkaTemplate = kafkaTemplate,
            topics = AiOutboxEventType.entries.associateWith(properties::topicOf)
        )
    }
}
