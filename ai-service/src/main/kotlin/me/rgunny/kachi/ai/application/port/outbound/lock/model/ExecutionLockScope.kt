package me.rgunny.kachi.ai.application.port.outbound.lock.model

/**
 * 실행 lock이 미치는 범위.
 *
 * 이 인스턴스 안에서만 겹치지 않으면 되는 작업과, 어느 인스턴스에서든 한 번만 돌아야 하는 작업은 다르다.
 * 범위를 좁게 잡으면 중복이 나고, 넓게 잡으면 나눠 처리할 수 있는 일을 한 대에 묶는다.
 * 무엇이 이 범위를 지키는지는 adapter가 정한다.
 */
enum class ExecutionLockScope {

    /**
     * 이 인스턴스 안에서만 겹치지 않으면 되는 작업.
     *
     * 여러 인스턴스가 함께 실행해도 되는 근거가 작업마다 따로 있어야 한다.
     */
    INSTANCE,

    /**
     * 어느 인스턴스에서든 한 번만 실행되어야 하는 작업.
     */
    CLUSTER
}
