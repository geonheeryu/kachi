package me.rgunny.kachi.notification.routing.domain

/**
 * routing job 상태.
 *
 * STARTED -> COMPLETED
 *
 * STARTED는 라우팅 중이거나 라우팅 도중 중단된 상태다. 같은 이벤트가 다시 오면 이어서 진행한다.
 */
enum class RoutingJobStatus {
    STARTED,
    COMPLETED,
}
