package me.rgunny.kachi.user.application.port.inbound.auth.model

import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole

/** access token이 가리키는 사용자와 역할 */
data class AccessTokenAuthentication(
    val userId: UserId,
    val role: UserRole
)
