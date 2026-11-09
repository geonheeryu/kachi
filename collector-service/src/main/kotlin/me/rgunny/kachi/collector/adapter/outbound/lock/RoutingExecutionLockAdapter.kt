package me.rgunny.kachi.collector.adapter.outbound.lock

import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.collector.application.port.outbound.lock.ExecutionLockPort
import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockScope
import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockTarget

/**
 * 실행 단위가 선언한 범위에 맞는 lock 구현으로 넘기는 라우터.
 *
 * 호출자는 자기 작업의 범위를 알지만 그 범위를 무엇이 구현하는지는 모른다.
 * 범위마다 저장소가 달라져도 바뀌는 것은 이 연결뿐이고, executor와 진입점은 그대로다.
 *
 * 범위가 하나라도 비어 있으면 그 범위의 작업이 조용히 lock 없이 도는 대신 기동에서 실패한다.
 */
class RoutingExecutionLockAdapter(
    private val delegates: Map<ExecutionLockScope, ExecutionLockPort>
) : ExecutionLockPort {

    init {
        val unmapped = ExecutionLockScope.entries.filterNot { it in delegates }

        require(unmapped.isEmpty()) {
            "lock 구현이 연결되지 않은 범위가 있습니다: $unmapped"
        }
    }

    override suspend fun <T> withLock(
        target: ExecutionLockTarget,
        action: suspend () -> T
    ): ExecutionLockOutcome<T> {
        return delegates.getValue(target.scope).withLock(target, action)
    }
}
