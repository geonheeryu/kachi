package me.rgunny.kachi.notification.routing.exception.routing

import me.rgunny.kachi.notification.routing.exception.ErrorCode

enum class RoutingErrorCode(
    override val code: String,
    override val message: String,
) : ErrorCode {
    ROUTING_JOB_CONFLICT("NOTIFICATION_ROUTING_JOB_CONFLICT", "같은 이벤트의 routing job이 이미 있습니다"),
    ROUTING_JOB_TRANSITION_CONFLICT("NOTIFICATION_ROUTING_JOB_TRANSITION_CONFLICT", "routing job이 전이 전 상태와 달라 전이를 저장하지 못했습니다"),
    USER_SERVICE_REQUEST_FAILED("NOTIFICATION_ROUTING_USER_SERVICE_REQUEST_FAILED", "user-service 수신자 조회 요청에 실패했습니다"),
    USER_SERVICE_RESPONSE_FAILED("NOTIFICATION_ROUTING_USER_SERVICE_RESPONSE_FAILED", "user-service 수신자 조회가 실패 응답을 돌려줬습니다"),
    USER_SERVICE_RESPONSE_MISSING_DATA("NOTIFICATION_ROUTING_USER_SERVICE_RESPONSE_MISSING_DATA", "user-service 수신자 조회 응답에 data가 없습니다"),
    USER_SERVICE_RESPONSE_INVALID("NOTIFICATION_ROUTING_USER_SERVICE_RESPONSE_INVALID", "user-service 수신자 조회 응답을 해석할 수 없습니다"),
}
