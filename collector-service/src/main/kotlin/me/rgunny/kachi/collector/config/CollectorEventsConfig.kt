package me.rgunny.kachi.collector.config

import me.rgunny.kachi.collector.adapter.outbound.outbox.KafkaCollectorOutboxPublisher
import me.rgunny.kachi.collector.application.port.outbound.outbox.CollectorOutboxPublisherPort
import me.rgunny.kachi.collector.domain.outbox.CollectorOutboxEventType
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate

/**
 * 이벤트 발행이 켜져 있을 때만 Kafka 발행 어댑터와 topic 선언을 조립한다.
 *
 * 꺼진 인스턴스에는 발행 포트 구현이 없다.
 * relay가 켜져 있다면 그 사실을 relay 조립이 기동 실패로 알린다.
 *
 * topic 선언은 기사 이벤트의 보존 규칙(기사 id compaction + 기간 삭제)을 producer 쪽 코드에 남기기 위한 것이다.
 * broker에 이미 있는 topic의 설정은 바꾸지 않는다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = CollectorEventsProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true"
)
class CollectorEventsConfig {

    @Bean
    fun kafkaCollectorOutboxPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        properties: CollectorEventsProperties
    ): CollectorOutboxPublisherPort {
        return KafkaCollectorOutboxPublisher(
            kafkaTemplate = kafkaTemplate,
            topics = CollectorOutboxEventType.entries.associateWith(properties::topicOf)
        )
    }

    @Bean
    fun newsCollectedTopic(properties: CollectorEventsProperties): NewTopic {
        return TopicBuilder.name(properties.topics.newsCollected)
            .config(TopicConfig.CLEANUP_POLICY_CONFIG, "${TopicConfig.CLEANUP_POLICY_COMPACT},${TopicConfig.CLEANUP_POLICY_DELETE}")
            .config(TopicConfig.RETENTION_MS_CONFIG, properties.retention.toMillis().toString())
            .build()
    }
}
