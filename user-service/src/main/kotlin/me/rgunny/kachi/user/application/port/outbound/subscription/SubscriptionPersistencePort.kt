package me.rgunny.kachi.user.application.port.outbound.subscription

import me.rgunny.kachi.user.domain.KeywordId
import me.rgunny.kachi.user.domain.Subscription
import me.rgunny.kachi.user.domain.SubscriptionId
import me.rgunny.kachi.user.domain.UserId

/**
 * 구독 저장소 출력 포트.
 *
 * (user, keyword) 중복 판정, 사용자별 목록, 키워드별 enabled 구독 조회, 수신자 페이지용 keyset 조회를 제공한다.
 */
interface SubscriptionPersistencePort {
    fun findById(subscriptionId: SubscriptionId): Subscription?

    fun findAllByUserId(userId: UserId): List<Subscription>

    /** 키워드의 enabled 구독 */
    fun findAllEnabledByKeywordId(keywordId: KeywordId): List<Subscription>

    /**
     * 키워드의 enabled 구독 사용자 중 [cursor] 다음부터 [limit]명.
     *
     * 순서는 userId의 부호 없는 16바이트 오름차순이다.
     * [cursor]가 null이면 처음부터 읽는다.
     */
    fun findEnabledUserIdsByKeywordId(keywordId: KeywordId, cursor: UserId?, limit: Int): List<UserId>

    /** [keywordIds] x [userIds]의 enabled 구독. 순서는 정하지 않는다. */
    fun findAllEnabledByKeywordIdsAndUserIds(keywordIds: Set<KeywordId>, userIds: Set<UserId>): List<Subscription>

    fun existsByUserIdAndKeywordId(userId: UserId, keywordId: KeywordId): Boolean

    fun save(subscription: Subscription): Subscription
}
