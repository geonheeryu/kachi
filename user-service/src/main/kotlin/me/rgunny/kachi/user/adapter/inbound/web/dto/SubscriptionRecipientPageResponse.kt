package me.rgunny.kachi.user.adapter.inbound.web.dto

import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientPageResult

/**
 * 구독 수신자 조회 internal API 응답.
 *
 * 수신자 항목의 `keywordIndexes`는 [keywords]의 위치다.
 * [nextCursor]가 null이면 마지막 페이지다.
 */
data class SubscriptionRecipientPageResponse(
    val keywords: List<SubscriptionRecipientKeywordResponse>,
    val recipients: List<SubscriptionRecipientResponse>,
    val nextCursor: String?
) {

    companion object {

        fun from(result: SubscriptionRecipientPageResult): SubscriptionRecipientPageResponse {
            return SubscriptionRecipientPageResponse(
                keywords = result.keywords.map(SubscriptionRecipientKeywordResponse::from),
                recipients = result.recipients.map(SubscriptionRecipientResponse::from),
                nextCursor = result.nextCursor?.value?.toString()
            )
        }
    }
}
