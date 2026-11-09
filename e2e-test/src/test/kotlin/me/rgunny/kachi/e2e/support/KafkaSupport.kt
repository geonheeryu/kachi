package me.rgunny.kachi.e2e.support

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 테스트 JVM이 broker의 레코드를 읽고 쓴다. 서비스가 발행한 것을 계약 타입으로 단언하거나, 같은 레코드를 다시 넣어 멱등을 본다.
 */
class KafkaSupport(private val bootstrapServers: String) {

    /** topic 처음부터 읽어 [matches]인 레코드를 [expected]개 모을 때까지, 또는 [timeout]까지 기다린다. */
    fun recordsOf(
        topic: String,
        expected: Int,
        timeout: Duration = DEFAULT_TIMEOUT,
        matches: (ConsumerRecord<String, String>) -> Boolean
    ): List<ConsumerRecord<String, String>> {
        val config = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "e2e-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )
        KafkaConsumer<String, String>(config).use { consumer ->
            consumer.subscribe(listOf(topic))
            val found = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = Instant.now().plus(timeout)
            while (found.size < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach { if (matches(it)) found += it }
            }
            return found
        }
    }

    fun send(topic: String, key: String, value: String) {
        val config = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all"
        )
        KafkaProducer<String, String>(config).use { producer ->
            producer.send(ProducerRecord(topic, key, value)).get()
        }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val TOPIC_STORY_ARTICLE_ATTACHED = "story.article.attached"
        const val TOPIC_SUMMARY_CREATED = "ai.summary.created"
        const val TOPIC_STORY_QUARANTINED = "ai.story.quarantined"
        const val TOPIC_NOTIFICATION_REQUESTED = "notification.requested"
    }
}
