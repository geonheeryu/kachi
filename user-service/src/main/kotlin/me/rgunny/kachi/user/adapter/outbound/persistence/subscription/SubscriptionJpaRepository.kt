package me.rgunny.kachi.user.adapter.outbound.persistence.subscription

import java.util.UUID
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `subscriptions` 테이블 Spring Data 저장소.
 *
 * 사용자 기준 목록, 키워드 기준 enabled 목록, (user, keyword) 존재 여부를 제공한다. 채널 컬렉션은 엔티티가 함께 적재한다.
 *
 * userId keyset 조회는 `(keyword_id, enabled, user_id)` 인덱스를 user_id 순으로 읽고 [Limit]에서 멈춘다.
 * user_id 비교와 정렬은 `BINARY(16)`의 부호 없는 바이트 순이다.
 */
interface SubscriptionJpaRepository : JpaRepository<SubscriptionJpaEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<SubscriptionJpaEntity>

    fun findAllByKeywordIdAndEnabledTrue(keywordId: UUID): List<SubscriptionJpaEntity>

    @Query(
        """
        select s.userId from SubscriptionJpaEntity s
        where s.keywordId = :keywordId and s.enabled = true
        order by s.userId
        """
    )
    fun findEnabledUserIdsByKeywordId(
        @Param("keywordId") keywordId: UUID,
        limit: Limit
    ): List<UUID>

    @Query(
        """
        select s.userId from SubscriptionJpaEntity s
        where s.keywordId = :keywordId and s.enabled = true and s.userId > :cursor
        order by s.userId
        """
    )
    fun findEnabledUserIdsByKeywordIdAfter(
        @Param("keywordId") keywordId: UUID,
        @Param("cursor") cursor: UUID,
        limit: Limit
    ): List<UUID>

    /** 채널 컬렉션을 fetch join으로 함께 적재한 enabled 구독. */
    @Query(
        """
        select distinct s from SubscriptionJpaEntity s
        join fetch s.channels
        where s.keywordId in :keywordIds and s.userId in :userIds and s.enabled = true
        """
    )
    fun findAllEnabledByKeywordIdInAndUserIdIn(
        @Param("keywordIds") keywordIds: Collection<UUID>,
        @Param("userIds") userIds: Collection<UUID>
    ): List<SubscriptionJpaEntity>

    fun findByUserIdAndKeywordId(userId: UUID, keywordId: UUID): SubscriptionJpaEntity?

    fun existsByUserIdAndKeywordId(userId: UUID, keywordId: UUID): Boolean
}
