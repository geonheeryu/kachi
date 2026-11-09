package me.rgunny.kachi.user.adapter.inbound.web.dto

import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientKeywordResult

/**
 * 구독 수신자 조회 응답의 키워드 한 건.
 */
data class SubscriptionRecipientKeywordResponse(
    val canonicalKey: String,
    val displayName: String
) {

    companion object {

        fun from(result: SubscriptionRecipientKeywordResult): SubscriptionRecipientKeywordResponse {
            return SubscriptionRecipientKeywordResponse(
                canonicalKey = result.canonicalKey,
                displayName = result.displayName
            )
        }
    }
}
