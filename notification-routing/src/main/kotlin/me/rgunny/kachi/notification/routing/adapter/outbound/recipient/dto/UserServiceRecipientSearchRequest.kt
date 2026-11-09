package me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto

/**
 * 구독 수신자 조회 요청 body.
 *
 * cursor는 앞 페이지의 nextCursor이고 첫 페이지면 null이다.
 * limit은 한 페이지의 사용자 수다.
 */
data class UserServiceRecipientSearchRequest(
    val keywords: List<String>,
    val cursor: String?,
    val limit: Int,
)
