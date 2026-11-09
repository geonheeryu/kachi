package me.rgunny.kachi.collector.adapter.outbound.outbox

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.application.exception.CollectorOutboxErrorCode
import me.rgunny.kachi.collector.application.exception.CollectorOutboxPublishException
import me.rgunny.kachi.collector.domain.outbox.CollectorOutboxEventType
import me.rgunny.kachi.collector.fake.FakeKafkaTemplate
import me.rgunny.kachi.collector.fixture.CollectorTestFixture
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("KafkaCollectorOutboxPublisher")
class KafkaCollectorOutboxPublisherTest {

    private val kafkaTemplate = FakeKafkaTemplate()
    private val properties = CollectorTestFixture.eventsProperties()
    private val publisher = KafkaCollectorOutboxPublisher(
        kafkaTemplate = kafkaTemplate,
        topics = CollectorOutboxEventType.entries.associateWith(properties::topicOf)
    )

    @ParameterizedTest
    @EnumSource(CollectorOutboxEventType::class)
    @DisplayName("행의 eventType이 고른 topic으로 레코드 하나를 보낸다")
    fun sendOneRecordToTopicOfEventType(eventType: CollectorOutboxEventType) = runBlocking {
        val outbox = CollectorTestFixture.outbox(eventType = eventType)

        publisher.publish(outbox)

        val record = kafkaTemplate.sent.single()
        assertEquals(properties.topicOf(eventType), record.topic)
    }

    @Test
    @DisplayName("key는 partitionKey, value는 기록된 payload 원문이다")
    fun sendPartitionKeyAndPayloadAsIs() = runBlocking {
        val outbox = CollectorTestFixture.outbox(partitionKey = "NVIDIA", payload = """{"schemaVersion":1,"newsId":"n-1"}""")

        publisher.publish(outbox)

        val record = kafkaTemplate.sent.single()
        assertEquals("NVIDIA", record.key)
        assertEquals(outbox.payload, record.value)
    }

    @Test
    @DisplayName("broker 일시 장애는 재시도 대상 실패로 던진다")
    fun throwRetryableOnTransientFailure() = runBlocking {
        kafkaTemplate.failureFor = { TimeoutException("timeout") }

        val error = assertFailsWith<CollectorOutboxPublishException> { publisher.publish(CollectorTestFixture.outbox()) }

        assertEquals(CollectorOutboxErrorCode.OUTBOX_PUBLISH_FAILED, error.errorCode)
        assertTrue(error.retryable)
    }

    @Test
    @DisplayName("같은 payload를 다시 보내도 같은 실패면 재시도하지 않는 실패로 던진다")
    fun throwNonRetryableOnRecordTooLarge() = runBlocking {
        kafkaTemplate.failureFor = { RecordTooLargeException("too large") }

        val error = assertFailsWith<CollectorOutboxPublishException> { publisher.publish(CollectorTestFixture.outbox()) }

        assertEquals(CollectorOutboxErrorCode.OUTBOX_PUBLISH_FAILED, error.errorCode)
        assertFalse(error.retryable)
    }

    @Test
    @DisplayName("실패 메시지에 topic과 eventKey를 남긴다")
    fun describeFailureWithTopicAndEventKey() = runBlocking {
        kafkaTemplate.failureFor = { TimeoutException("timeout") }
        val outbox = CollectorTestFixture.outbox(eventKey = "q-1")

        val error = assertFailsWith<CollectorOutboxPublishException> { publisher.publish(outbox) }

        val message = error.message!!
        assertTrue(message.contains("topic=${CollectorTestFixture.EVENT_TOPIC_NEWS_COLLECTED}"), message)
        assertTrue(message.contains("eventKey=q-1"), message)
    }
}
