package me.rgunny.kachi.notification.routing.application.port.outbound.job

import me.rgunny.kachi.notification.routing.domain.RoutingJob

/**
 * routing job 영속화 port.
 *
 * eventKey는 저장소 unique 제약으로 보호해야 한다. insert가 그 제약에 걸리면
 * RoutingJobConflictException을 던지고, 호출자는 다시 조회해 기존 job으로 진행한다.
 */
interface RoutingJobPersistencePort {

    suspend fun insert(job: RoutingJob): RoutingJob

    suspend fun findByEventKey(eventKey: String): RoutingJob?

    /**
     * 저장된 job이 previous의 id, status, cursor와 같을 때만 next로 갱신하고 갱신된 job을 돌려준다.
     *
     * previous와 next는 같은 job이어야 한다.
     * 조건이 어긋나면 아무것도 바꾸지 않고 RoutingJobTransitionConflictException을 던진다.
     */
    suspend fun saveTransition(previous: RoutingJob, next: RoutingJob): RoutingJob
}
