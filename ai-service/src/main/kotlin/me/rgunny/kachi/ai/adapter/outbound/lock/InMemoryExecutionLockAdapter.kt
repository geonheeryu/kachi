package me.rgunny.kachi.ai.adapter.outbound.lock

import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockHolder
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockTarget
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 이 인스턴스 안에서만 유효한 실행 lock.
 *
 * 프로세스가 사라지면 lock을 담은 map도 함께 사라지므로, 소유자가 사라진 뒤의 임대 상한을 따로 다루지 않아도
 * 포트의 약속이 지켜진다. 판단 자체가 실패할 수 없어 Unavailable도 내지 않는다.
 * 이 구현에서 소유자는 언제나 이 인스턴스다.
 *
 * 대상마다 lock이 독립이어야 하므로 키로 나눠 담는다. 서로 다른 작업이 서로를 막지 않는다.
 */
class InMemoryExecutionLockAdapter(
    private val clock: Clock
) : ExecutionLockPort {

    private val holders = ConcurrentHashMap<String, ExecutionLockHolder>()

    override suspend fun <T> withLock(
        target: ExecutionLockTarget,
        action: suspend () -> T
    ): ExecutionLockOutcome<T> {
        // 1. 확인과 채움이 한 연산이라 동시에 들어온 요청 중 정확히 하나만 통과한다.
        // get으로 비어 있는지 본 뒤 put으로 채우면 둘 사이에 다른 요청이 끼어들어 둘 다 통과할 수 있다.
        val holder = ExecutionLockHolder(acquiredAt = Instant.now(clock), owner = LOCAL_OWNER)
        val currentHolder = holders.putIfAbsent(target.key, holder)
        if (currentHolder != null) {
            return ExecutionLockOutcome.AlreadyHeld(currentHolder)
        }

        // 2. lock을 얻은 요청만 작업을 실행한다.
        return try {
            ExecutionLockOutcome.Executed(action())
        } finally {
            // 3. 작업이 예외로 끝나도 반드시 풀어, 한 번의 실패로 이후 모든 요청이 막히지 않게 한다.
            // 내가 넣은 값일 때만 지워서 남의 실행을 대신 풀어버리지 않는다.
            holders.remove(target.key, holder)
        }
    }

    private companion object {
        const val LOCAL_OWNER = "this-instance"
    }
}
