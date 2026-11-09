package me.rgunny.kachi.collector.application.port.outbound.lock.model

/**
 * collector-service가 lock으로 보호하는 실행 단위.
 *
 * 새 실행 단위가 생기면 여기에 상수를 더하며, 그때 범위를 정하지 않고는 넘어갈 수 없다.
 */
enum class CollectorExecutionLock(
    override val key: String,
    override val scope: ExecutionLockScope
) : ExecutionLockTarget {

    /**
     * 외부 provider 호출과 rate limit이 걸려 있어 어느 인스턴스에서든 한 번만 돌아야 한다.
     */
    NEWS_COLLECTION("collector-service:news-collection", ExecutionLockScope.CLUSTER),

    /**
     * 같은 행을 두 번 발행하는 것은 저장소의 조건부 쓰기가 막는다.
     * 여기서 막는 것은 앞선 tick이 끝나기 전에 다음 tick이 같은 행을 다시 조회하는 낭비다.
     */
    OUTBOX_RELAY("collector-service:outbox-relay", ExecutionLockScope.INSTANCE)
}
