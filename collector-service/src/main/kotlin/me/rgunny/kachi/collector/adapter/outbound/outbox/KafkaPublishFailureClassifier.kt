package me.rgunny.kachi.collector.adapter.outbound.outbox

import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.SerializationException

/**
 * 재시도가 무의미한 Kafka 전송 실패를 가려내는 classifier.
 *
 * 같은 payload를 다시 보내도 결과가 같은 실패만 재시도하지 않고, 나머지는 전부 재시도한다.
 * 원인을 모르는 실패를 재시도하지 않는 쪽으로 두면 운영자가 매번 복구해야 하므로 기본값은 재시도다.
 * 권한 오류와 topic 없음도 재시도 쪽이다.
 * 재시도 한도 안에 설정이 고쳐지면 스스로 회복하고 아니면 DEAD로 간다.
 *
 * KafkaTemplate은 실패를 한 번 감싸 돌려주므로 cause 체인을 끝까지 훑는다.
 */
object KafkaPublishFailureClassifier {

    fun isRetryable(exception: Throwable): Boolean {
        return !exception.hasCauseMatching {
            it is RecordTooLargeException ||
                it is SerializationException ||
                it is InvalidTopicException
        }
    }

    private fun Throwable.hasCauseMatching(predicate: (Throwable) -> Boolean): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (predicate(current)) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
