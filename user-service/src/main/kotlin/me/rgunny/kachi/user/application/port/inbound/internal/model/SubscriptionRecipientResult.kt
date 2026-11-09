package me.rgunny.kachi.user.application.port.inbound.internal.model

import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId

/**
 * 수신자 한 명의 한 채널과 그 채널로 구독한 키워드.
 *
 * [keywordIndexes]는 같은 페이지 [SubscriptionRecipientPageResult.keywords]의 위치이며 오름차순이고 비어 있지 않다.
 */
data class SubscriptionRecipientResult(
    val userId: UserId,
    val channel: SubscriptionChannel,
    val keywordIndexes: List<Int>
)
