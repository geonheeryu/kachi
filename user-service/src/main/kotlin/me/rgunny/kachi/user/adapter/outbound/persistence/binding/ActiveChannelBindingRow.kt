package me.rgunny.kachi.user.adapter.outbound.persistence.binding

import java.util.UUID
import me.rgunny.kachi.user.domain.SubscriptionChannel

/**
 * ACTIVE 바인딩의 `user_id`, `channel` 두 컬럼만 읽는 JPQL projection 행.
 */
data class ActiveChannelBindingRow(
    val userId: UUID,
    val channel: SubscriptionChannel
)
