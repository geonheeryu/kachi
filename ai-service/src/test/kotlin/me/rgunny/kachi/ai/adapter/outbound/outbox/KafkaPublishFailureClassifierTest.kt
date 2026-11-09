package me.rgunny.kachi.ai.adapter.outbound.outbox

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaProducerException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("KafkaPublishFailureClassifier")
class KafkaPublishFailureClassifierTest {

    @Test
    @DisplayName("같은 레코드를 다시 보내도 결과가 같은 실패는 재시도하지 않는다")
    fun classifyNonRetryable() {
        listOf(
            RecordTooLargeException("too large"),
            SerializationException("serialization"),
            InvalidTopicException("invalid topic")
        ).forEach { error ->
            assertFalse(KafkaPublishFailureClassifier.isRetryable(error), error::class.simpleName)
        }
    }

    @Test
    @DisplayName("broker 일시 장애는 재시도한다")
    fun classifyRetryable() {
        listOf(
            TimeoutException("timeout"),
            NetworkException("network"),
            NotLeaderOrFollowerException("leader changed")
        ).forEach { error ->
            assertTrue(KafkaPublishFailureClassifier.isRetryable(error), error::class.simpleName)
        }
    }

    /**
     * 설정을 고쳐야 풀리는 문제지만 재시도 한도 안에 고쳐지면 스스로 회복한다. 즉시 DEAD로 보내서 얻는 것이 없다.
     */
    @Test
    @DisplayName("권한 오류와 topic 없음은 재시도한다")
    fun classifyConfigurationErrorsAsRetryable() {
        assertTrue(KafkaPublishFailureClassifier.isRetryable(TopicAuthorizationException("denied")))
        assertTrue(KafkaPublishFailureClassifier.isRetryable(UnknownTopicOrPartitionException("unknown")))
    }

    @Test
    @DisplayName("원인을 모르는 실패는 재시도한다")
    fun classifyUnknownAsRetryable() {
        assertTrue(KafkaPublishFailureClassifier.isRetryable(IllegalStateException("unknown")))
    }

    @Test
    @DisplayName("template이 감싼 예외는 cause를 끝까지 풀어 분류한다")
    fun unwrapProducerException() {
        val wrapped = KafkaProducerException(
            ProducerRecord("topic", "key", "value"),
            "Failed to send",
            RuntimeException("outer", RecordTooLargeException("too large"))
        )

        assertFalse(KafkaPublishFailureClassifier.isRetryable(wrapped))
    }
}
