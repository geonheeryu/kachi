package me.rgunny.kachi.user.application.port.inbound.internal

import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscriptionRecipientsQuery
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientPageResult

/**
 * 여러 키워드의 수신자를 사용자 단위 페이지로 찾는 유스케이스.
 *
 * 수신자는 `(userId, channel)`이며 그 채널로 구독한 키워드를 [SubscriptionRecipientPageResult.keywords]의 위치로 가리킨다.
 */
interface FindSubscriptionRecipientsUseCase {

    /** 정규화하면 빈 값이 되거나 100자를 넘는 키워드가 있으면 [IllegalArgumentException]을 던진다. */
    fun findRecipients(query: FindSubscriptionRecipientsQuery): SubscriptionRecipientPageResult
}
