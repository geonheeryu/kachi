package me.rgunny.kachi.user.adapter.inbound.web.dto

import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientResult
import me.rgunny.kachi.user.domain.SubscriptionChannel

/**
 * 구독 수신자 조회 응답의 수신자 한 건.
 *
 * 주소와 키워드 문자열은 싣지 않는다.
 * [keywordIndexes]는 같은 응답 `keywords`의 위치이며 오름차순이다.
 */
data class SubscriptionRecipientResponse(
    val userId: String,
    val channel: SubscriptionChannel,
    val keywordIndexes: List<Int>
) {

    companion object {

        fun from(result: SubscriptionRecipientResult): SubscriptionRecipientResponse {
            return SubscriptionRecipientResponse(
                userId = result.userId.value.toString(),
                channel = result.channel,
                keywordIndexes = result.keywordIndexes
            )
        }
    }
}
