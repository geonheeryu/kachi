package me.rgunny.kachi.notification.routing.exception.routing

/**
 * 저장된 routing job이 전이 전 상태(status, cursor)와 달라 전이 저장이 반영되지 않은 실패.
 *
 * 저장된 job은 바뀌지 않는다.
 */
class RoutingJobTransitionConflictException(
    val eventKey: String,
    val expectedCursor: String?,
) : RoutingException(
    errorCode = RoutingErrorCode.ROUTING_JOB_TRANSITION_CONFLICT,
    message = "routing job transition conflict. eventKey=$eventKey, expectedCursor=$expectedCursor",
)
