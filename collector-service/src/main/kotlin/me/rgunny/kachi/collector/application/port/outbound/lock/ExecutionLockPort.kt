package me.rgunny.kachi.collector.application.port.outbound.lock

import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockTarget

/**
 * 같은 작업이 동시에 두 번 실행되지 않게 하는 실행 lock.
 *
 * 호출자는 "한 번에 하나만"이라는 규칙만 선언하고, 그 규칙을 무엇이 어떻게 지키는지는 알지 않는다.
 * 어디까지 막을지는 [ExecutionLockTarget.scope]가 말하고, 그 범위를 구현하는 저장소는 adapter가 고른다.
 *
 * 구현은 아래 약속을 지킨다.
 * - lock을 얻으면 action을 정확히 한 번 실행하고 [ExecutionLockOutcome.Executed]를 반환한다.
 * - 다른 실행이 쥐고 있으면 action을 실행하지 않고 [ExecutionLockOutcome.AlreadyHeld]를 반환한다.
 * - lock을 판단할 수 없으면 action을 실행하지 않고 [ExecutionLockOutcome.Unavailable]을 반환한다.
 * - action이 예외를 던지면 lock을 풀고 그 예외를 그대로 올린다.
 *
 * 임대 시간은 소유자가 사라졌을 때 lock이 회수되기까지의 상한이며, 소유자가 살아 있는 동안에는 유지된다.
 * 임대가 작업보다 짧아 다른 실행이 끼어드는 것은 lock을 걸지 않은 것보다 나쁘므로, 연장이 필요하면 adapter가 맡는다.
 * 임대 시간은 대상마다 설정이 정하고 호출자는 넘기지 않는다.
 */
interface ExecutionLockPort {

    suspend fun <T> withLock(
        target: ExecutionLockTarget,
        action: suspend () -> T
    ): ExecutionLockOutcome<T>
}
