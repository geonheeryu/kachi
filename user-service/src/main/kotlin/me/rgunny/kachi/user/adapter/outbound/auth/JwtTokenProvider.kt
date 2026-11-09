package me.rgunny.kachi.user.adapter.outbound.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import me.rgunny.kachi.user.application.port.outbound.auth.TokenPort
import me.rgunny.kachi.user.application.token.IssuedToken
import me.rgunny.kachi.user.application.token.ParsedToken
import me.rgunny.kachi.user.application.token.TokenType
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

class JwtTokenProvider(
    secret: String,
    private val accessTokenTtl: Duration,
    private val refreshTokenTtl: Duration,
    private val clock: Clock
) : TokenPort {
    private val secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))

    fun createAccessToken(
        userId: UserId,
        role: UserRole
    ): JwtToken {
        return createToken(
            userId = userId,
            type = JwtTokenType.ACCESS,
            ttl = accessTokenTtl,
            claims = mapOf(ROLE_CLAIM to role.name)
        )
    }

    fun createRefreshToken(userId: UserId): JwtToken {
        return createToken(
            userId = userId,
            type = JwtTokenType.REFRESH,
            ttl = refreshTokenTtl,
            claims = emptyMap()
        )
    }

    override fun issueAccessToken(userId: UserId, role: UserRole): IssuedToken {
        return createAccessToken(userId, role).toIssuedToken()
    }

    override fun issueRefreshToken(userId: UserId): IssuedToken {
        return createRefreshToken(userId).toIssuedToken()
    }

    override fun parseToken(token: String): ParsedToken {
        val claims = parse(token)

        return ParsedToken(
            id = claims.id,
            userId = claims.userId,
            type = TokenType.valueOf(claims.type.name),
            role = claims.role
        )
    }

    fun parse(token: String): JwtTokenClaims {
        val claims = Jwts.parser()
            .verifyWith(secretKey)
            .clock { Date.from(Instant.now(clock)) }
            .build()
            .parseSignedClaims(token)
            .payload

        val type = JwtTokenType.valueOf(requiredClaim(claims.get(TYPE_CLAIM, String::class.java), TYPE_CLAIM))
        val role = claims.get(ROLE_CLAIM, String::class.java)?.let(UserRole::valueOf)

        if (type == JwtTokenType.ACCESS) {
            require(role != null) { "access token에는 role claim이 필요합니다" }
        }

        return JwtTokenClaims(
            id = requiredClaim(claims.id, "jti"),
            userId = UserId.of(UUID.fromString(requiredClaim(claims.subject, "sub"))),
            type = type,
            role = role
        )
    }

    override fun isValid(token: String): Boolean {
        return try {
            parse(token)
            true
        } catch (exception: JwtException) {
            false
        } catch (exception: IllegalArgumentException) {
            false
        }
    }

    private fun JwtToken.toIssuedToken(): IssuedToken {
        return IssuedToken(
            id = id,
            value = value,
            expiresAt = expiresAt
        )
    }

    private fun createToken(
        userId: UserId,
        type: JwtTokenType,
        ttl: Duration,
        claims: Map<String, Any>
    ): JwtToken {
        require(!ttl.isNegative && !ttl.isZero) { "JWT 만료 시간은 0보다 커야 합니다" }

        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(ttl)
        val tokenId = UUID.randomUUID().toString()
        val token = Jwts.builder()
            .id(tokenId)
            .subject(userId.value.toString())
            .claim(TYPE_CLAIM, type.name)
            .claims(claims)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact()

        return JwtToken(
            id = tokenId,
            value = token,
            expiresAt = expiresAt
        )
    }

    companion object {
        private const val TYPE_CLAIM = "type"
        private const val ROLE_CLAIM = "role"

        private fun requiredClaim(
            value: String?,
            name: String
        ): String {
            return requireNotNull(value) { "JWT $name claim이 필요합니다" }
        }
    }
}
