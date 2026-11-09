package me.rgunny.kachi.user.adapter.outbound.auth

import me.rgunny.kachi.user.application.port.outbound.auth.RefreshTokenStorePort
import me.rgunny.kachi.user.application.token.StoredRefreshToken
import me.rgunny.kachi.user.domain.UserId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * refresh token jti를 Redis에 저장하고, 재발급 시 기존 token을 새 token으로 회전하는 출력 어댑터.
 *
 * Key 형식은 `refresh_token:{userId}:{jti}`이며, value는 존재 여부 확인용 고정 문자열만 저장한다.
 * 사용자 정보는 JWT와 DB에서 조회하고 Redis는 refresh token의 생존 여부만 판단한다.
 */
@Component
class RefreshTokenRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val rotateRefreshTokenScript: RedisScript<Long>,
    private val clock: Clock
) : RefreshTokenStorePort {

    /**
     * 최초 로그인처럼 refresh token을 새로 발급하는 흐름에서 호출한다.
     * Redis TTL은 JWT refresh token 만료 시각과 맞춘다.
     */
    override fun save(token: StoredRefreshToken) {
        val ttl = ttlUntil(token.expiresAt)
        if (ttl.isNegative || ttl.isZero) {
            return
        }

        redisTemplate.opsForValue().set(
            key(token.userId, token.id),
            VALUE,
            ttl
        )
    }

    /**
     * JWT 서명과 만료 검증을 통과한 refresh token이 서버 저장소에도 남아 있는지 확인한다.
     */
    override fun exists(userId: UserId, tokenId: String): Boolean {
        return redisTemplate.hasKey(key(userId, tokenId)) ?: false
    }

    /**
     * refresh token 재발급 시 기존 jti를 폐기하고 새 jti를 저장한다.
     * Lua script를 사용해 삭제와 저장 사이에 다른 요청이 끼어드는 것을 막는다. (원자적 실행)
     */
    override fun rotate(userId: UserId, oldTokenId: String, newToken: StoredRefreshToken): Boolean {
        val ttl = ttlUntil(newToken.expiresAt)
        if (ttl.isNegative || ttl.isZero) {
            return false
        }

        val result = redisTemplate.execute(
            rotateRefreshTokenScript,
            listOf(key(userId, oldTokenId), key(userId, newToken.id)),
            VALUE,
            ttl.toMillis().toString()
        )

        return result == 1L
    }

    /**
     * 로그아웃 시 해당 refresh token jti를 삭제해 추가 재발급을 막는다.
     */
    override fun revoke(userId: UserId, tokenId: String) {
        redisTemplate.delete(key(userId, tokenId))
    }

    private fun ttlUntil(expiresAt: Instant): Duration {
        return Duration.between(Instant.now(clock), expiresAt)
    }

    private fun key(userId: UserId, tokenId: String): String {
        return "$KEY_PREFIX:${userId.value}:$tokenId"
    }

    companion object {
        private const val KEY_PREFIX = "refresh_token"
        private const val VALUE = "1"
    }
}
