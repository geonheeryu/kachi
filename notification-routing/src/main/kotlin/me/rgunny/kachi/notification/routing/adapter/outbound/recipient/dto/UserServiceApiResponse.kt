package me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto

/**
 * user-service internal API 응답 envelope.
 *
 * 실패 응답의 error 본문은 쓰지 않으므로 받지 않는다.
 */
data class UserServiceApiResponse<T>(
    val success: Boolean,
    val data: T?,
)
