package me.rgunny.kachi.user.application.service.fake

import me.rgunny.kachi.user.application.port.outbound.binding.ChannelBindingPersistencePort
import me.rgunny.kachi.user.application.port.outbound.binding.model.ActiveChannelBinding
import me.rgunny.kachi.user.domain.ChannelBinding
import me.rgunny.kachi.user.domain.ChannelBindingId
import me.rgunny.kachi.user.domain.LinkTokenHash
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId

/**
 * 메모리 채널 바인딩 저장소.
 *
 * 초기 바인딩을 넣어 두고 서비스가 저장한 결과를 [savedBindings]로 확인한다. 주소는 평문 그대로 둔다.
 */
class FakeChannelBindingPersistencePort(
    bindings: List<ChannelBinding> = emptyList()
) : ChannelBindingPersistencePort {
    private val bindings = bindings.associateBy { it.id }.toMutableMap()
    val savedBindings = mutableListOf<ChannelBinding>()

    override fun findById(id: ChannelBindingId): ChannelBinding? = bindings[id]

    override fun findByUserIdAndChannel(userId: UserId, channel: SubscriptionChannel): ChannelBinding? {
        return bindings.values.singleOrNull { it.userId == userId && it.channel == channel }
    }

    override fun findAllByUserId(userId: UserId): List<ChannelBinding> = bindings.values.filter { it.userId == userId }

    override fun findAllByUserIds(userIds: Set<UserId>): List<ChannelBinding> = bindings.values.filter { it.userId in userIds }

    override fun findAllActiveByUserIds(userIds: Set<UserId>): List<ActiveChannelBinding> {
        return bindings.values
            .filter { it.userId in userIds && it.isActive }
            .map { ActiveChannelBinding(userId = it.userId, channel = it.channel) }
    }

    override fun findByLinkTokenHash(linkTokenHash: LinkTokenHash): ChannelBinding? {
        return bindings.values.singleOrNull { it.linkTokenHash == linkTokenHash }
    }

    override fun save(binding: ChannelBinding): ChannelBinding {
        savedBindings += binding
        bindings[binding.id] = binding
        return binding
    }
}
