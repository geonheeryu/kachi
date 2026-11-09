package me.rgunny.kachi.ai.application.port.outbound.lock.model

import java.time.Instant

/**
 * lock을 쥐고 있는 실행의 최소 정보.
 *
 * 막힌 요청에게 언제부터 누가 쥐고 있는지를 알린다. [owner]는 lock을 획득한 주체이며 범위에 따라 뜻이 다르다.
 */
data class ExecutionLockHolder(
    val acquiredAt: Instant,
    val owner: String
)

/**
 * 실행 lock을 거친 요청의 결과.
 *
 * 막힌 이유를 둘로 나눈다. 다른 실행이 쥐고 있어 막힌 것과 lock 자체를 판단할 수 없어 막힌 것은
 * 호출자의 대응이 다르다. 앞은 정상이고 다음 차례에 풀리지만, 뒤는 장애이며 사람이 봐야 한다.
 */
sealed interface ExecutionLockOutcome<out T> {

    /**
     * lock을 획득해 작업이 실행된 결과.
     */
    data class Executed<T>(
        val value: T
    ) : ExecutionLockOutcome<T>

    /**
     * 다른 실행이 lock을 쥐고 있어 작업을 실행하지 않은 결과.
     */
    data class AlreadyHeld(
        val holder: ExecutionLockHolder
    ) : ExecutionLockOutcome<Nothing>

    /**
     * lock을 확인할 수 없어 작업을 실행하지 않은 결과.
     */
    data class Unavailable(
        val cause: Throwable
    ) : ExecutionLockOutcome<Nothing>
}
