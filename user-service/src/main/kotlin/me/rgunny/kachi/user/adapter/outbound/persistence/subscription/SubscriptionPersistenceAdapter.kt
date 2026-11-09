package me.rgunny.kachi.user.adapter.outbound.persistence.subscription

import me.rgunny.kachi.user.application.port.outbound.subscription.SubscriptionPersistencePort
import me.rgunny.kachi.user.domain.KeywordId
import me.rgunny.kachi.user.domain.Subscription
import me.rgunny.kachi.user.domain.SubscriptionId
import me.rgunny.kachi.user.domain.UserId
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Repository

/**
 * 구독 저장소.
 * 도메인과 JPA 엔티티 사이 변환만 담당한다.
 */
@Repository
class SubscriptionPersistenceAdapter(
    private val subscriptionJpaRepository: SubscriptionJpaRepository
) : SubscriptionPersistencePort {

    override fun findById(subscriptionId: SubscriptionId): Subscription? {
        return subscriptionJpaRepository.findById(subscriptionId.value)
            .map { it.toDomain() }
            .orElse(null)
    }

    override fun findAllByUserId(userId: UserId): List<Subscription> {
        return subscriptionJpaRepository.findAllByUserId(userId.value)
            .map { it.toDomain() }
    }

    override fun findEnabledUserIdsByKeywordId(keywordId: KeywordId, cursor: UserId?, limit: Int): List<UserId> {
        val userIds = if (cursor == null) {
            subscriptionJpaRepository.findEnabledUserIdsByKeywordId(keywordId.value, Limit.of(limit))
        } else {
            subscriptionJpaRepository.findEnabledUserIdsByKeywordIdAfter(keywordId.value, cursor.value, Limit.of(limit))
        }

        return userIds.map(UserId::of)
    }

    override fun findAllEnabledByKeywordIdsAndUserIds(
        keywordIds: Set<KeywordId>,
        userIds: Set<UserId>
    ): List<Subscription> {
        if (keywordIds.isEmpty() || userIds.isEmpty()) return emptyList()

        return subscriptionJpaRepository.findAllEnabledByKeywordIdInAndUserIdIn(
            keywordIds = keywordIds.map { it.value },
            userIds = userIds.map { it.value }
        ).map { it.toDomain() }
    }

    override fun existsByUserIdAndKeywordId(userId: UserId, keywordId: KeywordId): Boolean {
        return subscriptionJpaRepository.existsByUserIdAndKeywordId(
            userId = userId.value,
            keywordId = keywordId.value
        )
    }

    override fun save(subscription: Subscription): Subscription {
        return subscriptionJpaRepository.save(SubscriptionJpaEntity.from(subscription)).toDomain()
    }
}
