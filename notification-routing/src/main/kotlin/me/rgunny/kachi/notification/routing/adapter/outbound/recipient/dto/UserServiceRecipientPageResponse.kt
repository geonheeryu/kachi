package me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto

/**
 * 구독 수신자 조회 응답 data.
 *
 * recipients 항목의 keywordIndexes는 keywords의 위치다.
 * nextCursor가 null이면 마지막 페이지다.
 */
data class UserServiceRecipientPageResponse(
    val keywords: List<UserServiceRecipientKeywordResponse>,
    val recipients: List<UserServiceRecipientResponse>,
    val nextCursor: String?,
)
