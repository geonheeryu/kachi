package me.rgunny.kachi.user.adapter.outbound.persistence.binding

import java.util.UUID
import me.rgunny.kachi.user.domain.ChannelBindingStatus
import me.rgunny.kachi.user.domain.SubscriptionChannel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `channel_bindings` 테이블 Spring Data 저장소.
 */
interface ChannelBindingJpaRepository : JpaRepository<ChannelBindingJpaEntity, UUID> {
    fun findByUserIdAndChannel(userId: UUID, channel: SubscriptionChannel): ChannelBindingJpaEntity?

    fun findAllByUserId(userId: UUID): List<ChannelBindingJpaEntity>

    fun findAllByUserIdIn(userIds: Collection<UUID>): List<ChannelBindingJpaEntity>

    /** [status] 바인딩의 `user_id`, `channel` projection. */
    @Query(
        """
        select new me.rgunny.kachi.user.adapter.outbound.persistence.binding.ActiveChannelBindingRow(b.userId, b.channel)
        from ChannelBindingJpaEntity b
        where b.userId in :userIds and b.status = :status
        """
    )
    fun findChannelsByUserIdInAndStatus(
        @Param("userIds") userIds: Collection<UUID>,
        @Param("status") status: ChannelBindingStatus
    ): List<ActiveChannelBindingRow>

    fun findByLinkTokenHash(linkTokenHash: ByteArray): ChannelBindingJpaEntity?
}
