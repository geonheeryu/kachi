package me.rgunny.kachi.user.adapter.outbound.persistence.binding

import me.rgunny.kachi.user.application.port.outbound.binding.AddressCipherPort
import me.rgunny.kachi.user.application.port.outbound.binding.ChannelBindingPersistencePort
import me.rgunny.kachi.user.application.port.outbound.binding.model.ActiveChannelBinding
import me.rgunny.kachi.user.domain.ChannelBinding
import me.rgunny.kachi.user.domain.ChannelBindingId
import me.rgunny.kachi.user.domain.ChannelBindingStatus
import me.rgunny.kachi.user.domain.LinkTokenHash
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId
import org.springframework.stereotype.Repository

/**
 * 채널 바인딩 저장소.
 * 도메인과 JPA 엔티티 사이 변환을 담당하며, 주소는 저장 시 암호화하고 읽을 때 복호화한다.
 */
@Repository
class ChannelBindingPersistenceAdapter(
    private val channelBindingJpaRepository: ChannelBindingJpaRepository,
    private val addressCipherPort: AddressCipherPort
) : ChannelBindingPersistencePort {

    override fun findById(id: ChannelBindingId): ChannelBinding? {
        return channelBindingJpaRepository.findById(id.value)
            .map { it.toDomain(addressCipherPort) }
            .orElse(null)
    }

    override fun findByUserIdAndChannel(userId: UserId, channel: SubscriptionChannel): ChannelBinding? {
        return channelBindingJpaRepository.findByUserIdAndChannel(userId.value, channel)?.toDomain(addressCipherPort)
    }

    override fun findAllByUserId(userId: UserId): List<ChannelBinding> {
        return channelBindingJpaRepository.findAllByUserId(userId.value)
            .map { it.toDomain(addressCipherPort) }
    }

    override fun findAllByUserIds(userIds: Set<UserId>): List<ChannelBinding> {
        if (userIds.isEmpty()) return emptyList()

        return channelBindingJpaRepository.findAllByUserIdIn(userIds.map { it.value })
            .map { it.toDomain(addressCipherPort) }
    }

    override fun findAllActiveByUserIds(userIds: Set<UserId>): List<ActiveChannelBinding> {
        if (userIds.isEmpty()) return emptyList()

        return channelBindingJpaRepository.findChannelsByUserIdInAndStatus(
            userIds = userIds.map { it.value },
            status = ChannelBindingStatus.ACTIVE
        ).map { ActiveChannelBinding(userId = UserId.of(it.userId), channel = it.channel) }
    }

    override fun findByLinkTokenHash(linkTokenHash: LinkTokenHash): ChannelBinding? {
        return channelBindingJpaRepository.findByLinkTokenHash(linkTokenHash.value)?.toDomain(addressCipherPort)
    }

    override fun save(binding: ChannelBinding): ChannelBinding {
        return channelBindingJpaRepository.save(ChannelBindingJpaEntity.from(binding, addressCipherPort))
            .toDomain(addressCipherPort)
    }
}
