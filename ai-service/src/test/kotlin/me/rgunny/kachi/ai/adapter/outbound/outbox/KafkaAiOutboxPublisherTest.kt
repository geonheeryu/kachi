package me.rgunny.kachi.ai.adapter.outbound.outbox

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.exception.AiOutboxErrorCode
import me.rgunny.kachi.ai.application.exception.AiOutboxPublishException
import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.fake.FakeKafkaTemplate
import me.rgunny.kachi.ai.fixture.AiTestFixture
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

@DisplayName("KafkaAiOutboxPublisher")
class KafkaAiOutboxPublisherTest {

    private val kafkaTemplate = FakeKafkaTemplate()
    private val properties = AiTestFixture.eventsProperties()
    private val publisher = KafkaAiOutboxPublisher(
        kafkaTemplate = kafkaTemplate,
        topics = AiOutboxEventType.entries.associateWith(properties::topicOf)
    )

    @ParameterizedTest
    @EnumSource(AiOutboxEventType::class)
    @DisplayName("행의 eventType이 고른 topic으로 레코드 하나를 보낸다")
    fun sendOneRecordToTopicOfEventType(eventType: AiOutboxEventType) = runBlocking {
        val outbox = AiTestFixture.outbox(eventType = eventType)

        publisher.publish(outbox)

        val record = kafkaTemplate.sent.single()
        assertEquals(properties.topicOf(eventType), record.topic)
    }

    @Test
    @DisplayName("key는 partitionKey, value는 기록된 payload 원문이다")
    fun sendPartitionKeyAndPayloadAsIs() = runBlocking {
        val outbox = AiTestFixture.outbox(partitionKey = "NVIDIA", payload = """{"schemaVersion":1,"summaryId":"s-1"}""")

        publisher.publish(outbox)

        val record = kafkaTemplate.sent.single()
        assertEquals("NVIDIA", record.key)
        assertEquals(outbox.payload, record.value)
    }

    @Test
    @DisplayName("broker 일시 장애는 재시도 대상 실패로 던진다")
    fun throwRetryableOnTransientFailure() = runBlocking {
        kafkaTemplate.failureFor = { TimeoutException("timeout") }

        val error = assertFailsWith<AiOutboxPublishException> { publisher.publish(AiTestFixture.outbox()) }

        assertEquals(AiOutboxErrorCode.OUTBOX_PUBLISH_FAILED, error.errorCode)
        assertTrue(error.retryable)
    }

    @Test
    @DisplayName("같은 payload를 다시 보내도 같은 실패면 재시도하지 않는 실패로 던진다")
    fun throwNonRetryableOnRecordTooLarge() = runBlocking {
        kafkaTemplate.failureFor = { RecordTooLargeException("too large") }

        val error = assertFailsWith<AiOutboxPublishException> { publisher.publish(AiTestFixture.outbox()) }

        assertEquals(AiOutboxErrorCode.OUTBOX_PUBLISH_FAILED, error.errorCode)
        assertFalse(error.retryable)
    }

    @Test
    @DisplayName("실패 메시지에 topic과 eventKey를 남긴다")
    fun describeFailureWithTopicAndEventKey() = runBlocking {
        kafkaTemplate.failureFor = { TimeoutException("timeout") }
        val outbox = AiTestFixture.outbox(eventType = AiOutboxEventType.KEYWORD_QUARANTINED, eventKey = "q-1")

        val error = assertFailsWith<AiOutboxPublishException> { publisher.publish(outbox) }

        val message = error.message!!
        assertTrue(message.contains("topic=${AiTestFixture.EVENT_TOPIC_KEYWORD_QUARANTINED}"), message)
        assertTrue(message.contains("eventKey=q-1"), message)
    }
}
