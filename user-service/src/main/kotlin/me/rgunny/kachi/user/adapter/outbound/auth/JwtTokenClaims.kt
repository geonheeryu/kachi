package me.rgunny.kachi.user.adapter.outbound.auth

import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole

data class JwtTokenClaims(
    val id: String,
    val userId: UserId,
    val type: JwtTokenType,
    val role: UserRole?
)
