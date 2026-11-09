package me.rgunny.kachi.ai.fake

import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockTarget

/**
 * 정해 둔 결과만 돌려주는 실행 lock.
 *
 * 실제 lock으로는 만들기 어려운 경우를 호출자가 어떻게 다루는지 보려고 쓴다.
 * [outcome]이 [ExecutionLockOutcome.Executed]가 아니면 action을 실행하지 않아, 막힌 요청이 작업을 부르지 않는지 확인할 수 있다.
 */
class FakeExecutionLockPort(
    private val outcome: ExecutionLockOutcome<Nothing>
) : ExecutionLockPort {

    var requestedTarget: ExecutionLockTarget? = null
        private set

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> withLock(
        target: ExecutionLockTarget,
        action: suspend () -> T
    ): ExecutionLockOutcome<T> {
        requestedTarget = target

        return outcome as ExecutionLockOutcome<T>
    }
}
