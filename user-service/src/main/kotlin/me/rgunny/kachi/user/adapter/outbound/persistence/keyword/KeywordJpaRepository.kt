package me.rgunny.kachi.user.adapter.outbound.persistence.keyword

import java.util.UUID
import me.rgunny.kachi.user.adapter.outbound.persistence.subscription.SubscriptionJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * `keywords` 테이블 Spring Data 저장소.
 *
 * canonicalKey 단건·다건 조회와 구독이 걸린 키워드 조회를 제공한다.
 */
interface KeywordJpaRepository : JpaRepository<KeywordJpaEntity, UUID> {
    fun findByCanonicalKey(canonicalKey: String): KeywordJpaEntity?

    fun findAllByCanonicalKeyIn(canonicalKeys: Collection<String>): List<KeywordJpaEntity>

    /** enabled 구독이 하나 이상 걸린 키워드. 구독 수와 무관하게 키워드당 한 행이다. */
    @Query(
        """
        select k from KeywordJpaEntity k
        where exists (
            select 1 from SubscriptionJpaEntity s
            where s.keywordId = k.id and s.enabled = true
        )
        """
    )
    fun findAllWithEnabledSubscription(): List<KeywordJpaEntity>
}
