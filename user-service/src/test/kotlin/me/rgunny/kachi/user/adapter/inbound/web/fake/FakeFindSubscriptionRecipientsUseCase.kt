package me.rgunny.kachi.user.adapter.inbound.web.fake

import me.rgunny.kachi.user.application.port.inbound.internal.FindSubscriptionRecipientsUseCase
import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscriptionRecipientsQuery
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientPageResult
import me.rgunny.kachi.user.domain.CanonicalKey

/**
 * 구독 수신자 조회 유스케이스 대역.
 *
 * [result]를 돌려주고 질의를 [query]에 남긴다.
 * 요청 키워드를 [CanonicalKey]로 정규화해 거부되는 값이면 [IllegalArgumentException]을 던진다.
 */
class FakeFindSubscriptionRecipientsUseCase : FindSubscriptionRecipientsUseCase {
    var result: SubscriptionRecipientPageResult = SubscriptionRecipientPageResult.empty()
    var query: FindSubscriptionRecipientsQuery? = null

    override fun findRecipients(query: FindSubscriptionRecipientsQuery): SubscriptionRecipientPageResult {
        this.query = query
        query.keywords.forEach(CanonicalKey::of)

        return result
    }
}
