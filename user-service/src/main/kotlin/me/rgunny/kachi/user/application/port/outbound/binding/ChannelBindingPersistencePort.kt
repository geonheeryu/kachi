package me.rgunny.kachi.user.application.port.outbound.binding

import me.rgunny.kachi.user.application.port.outbound.binding.model.ActiveChannelBinding
import me.rgunny.kachi.user.domain.ChannelBinding
import me.rgunny.kachi.user.domain.ChannelBindingId
import me.rgunny.kachi.user.domain.LinkTokenHash
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId

/**
 * 채널 바인딩 저장소 출력 포트.
 *
 * 저장소가 돌려주는 주소는 복호화된 평문이다. 암호화는 저장소 구현의 책임이다.
 */
interface ChannelBindingPersistencePort {
    fun findById(id: ChannelBindingId): ChannelBinding?

    fun findByUserIdAndChannel(userId: UserId, channel: SubscriptionChannel): ChannelBinding?

    fun findAllByUserId(userId: UserId): List<ChannelBinding>

    fun findAllByUserIds(userIds: Set<UserId>): List<ChannelBinding>

    /**
     * [userIds]의 ACTIVE 바인딩 `(userId, channel)`.
     *
     * 주소를 읽지도 복호화하지도 않는다. 순서는 정하지 않는다.
     */
    fun findAllActiveByUserIds(userIds: Set<UserId>): List<ActiveChannelBinding>

    fun findByLinkTokenHash(linkTokenHash: LinkTokenHash): ChannelBinding?

    fun save(binding: ChannelBinding): ChannelBinding
}
