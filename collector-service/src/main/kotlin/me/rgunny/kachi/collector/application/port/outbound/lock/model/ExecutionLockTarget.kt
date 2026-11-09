package me.rgunny.kachi.collector.application.port.outbound.lock.model

/**
 * lock으로 보호하는 실행 단위.
 *
 * 무엇을 보호하는지([key])와 어디까지 보호하는지([scope])는 코드가 정하고, 임대 시간만 설정이 정한다.
 * lock 키를 호출부에 문자열로 적으면 오타를 막을 수 없고 같은 대상이 두 이름으로 불릴 수 있다.
 */
interface ExecutionLockTarget {

    /**
     * lock을 식별하는 값. 여러 서비스가 같은 저장소를 쓸 수 있으므로 서비스 이름을 앞에 둔다.
     */
    val key: String

    val scope: ExecutionLockScope
}
