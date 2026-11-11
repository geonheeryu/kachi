package me.rgunny.kachi.user.application.port.inbound.internal.model

import me.rgunny.kachi.user.domain.UserId

/**
 * 수신자 조회 한 페이지.
 *
 * [keywords]는 요청 키워드 중 존재하는 것이며 canonicalKey 오름차순이다.
 * [recipients]는 userId(부호 없는 바이트 순), channel 순이다.
 * [nextCursor]는 다음 페이지의 커서이며 null이면 마지막 페이지다.
 */
data class SubscriptionRecipientPageResult(
    val keywords: List<SubscriptionRecipientKeywordResult>,
    val recipients: List<SubscriptionRecipientResult>,
    val nextCursor: UserId?
) {

    companion object {

        fun empty(): SubscriptionRecipientPageResult {
            return SubscriptionRecipientPageResult(
                keywords = emptyList(),
                recipients = emptyList(),
                nextCursor = null
            )
        }
    }
}
