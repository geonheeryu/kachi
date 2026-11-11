package me.rgunny.kachi.user.application.port.inbound.internal.model

import me.rgunny.kachi.user.domain.Keyword

/**
 * 수신자 조회 페이지의 키워드 한 건.
 */
data class SubscriptionRecipientKeywordResult(
    val canonicalKey: String,
    val displayName: String
) {

    companion object {

        fun from(keyword: Keyword): SubscriptionRecipientKeywordResult {
            return SubscriptionRecipientKeywordResult(
                canonicalKey = keyword.canonicalKey.value,
                displayName = keyword.displayName.value
            )
        }
    }
}
