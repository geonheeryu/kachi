package me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto

/**
 * 구독 수신자 조회 응답의 수신자 한 건.
 *
 * channel은 user-service의 enum 이름이다.
 * keywordIndexes는 같은 응답 keywords의 위치다.
 */
data class UserServiceRecipientResponse(
    val userId: String,
    val channel: String,
    val keywordIndexes: List<Int>,
)
