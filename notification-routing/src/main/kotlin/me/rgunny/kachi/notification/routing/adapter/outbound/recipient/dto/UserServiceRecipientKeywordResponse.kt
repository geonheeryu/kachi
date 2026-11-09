package me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto

/**
 * 구독 수신자 조회 응답의 키워드 한 건.
 */
data class UserServiceRecipientKeywordResponse(
    val canonicalKey: String,
    val displayName: String,
)
