package me.rgunny.kachi.user.application.port.outbound.binding.model

import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId

/**
 * 주소 없이 `(userId, channel)`만 담은 ACTIVE 바인딩.
 */
data class ActiveChannelBinding(
    val userId: UserId,
    val channel: SubscriptionChannel
)
