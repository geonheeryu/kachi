package me.rgunny.kachi.user.adapter.outbound.auth

import me.rgunny.kachi.user.application.token.StoredRefreshToken
import me.rgunny.kachi.user.config.RedisScriptConfig
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.fixture.UserTestFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("RefreshTokenRedisAdapter 통합 테스트")
class RefreshTokenRedisAdapterIntegrationTest {
    private val now = UserTestFixture.NOW
    private val clock = UserTestFixture.CLOCK
    private val redisTemplate = StringRedisTemplate(connectionFactory)
    private val refreshTokenRedisAdapter = RefreshTokenRedisAdapter(
        redisTemplate = redisTemplate,
        rotateRefreshTokenScript = RedisScriptConfig().rotateRefreshTokenScript(),
        clock = clock
    )

    @AfterEach
    fun flushRedis() {
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushDb()
    }

    @Nested
    @DisplayName("save()")
    inner class Save {

        @Test
        @DisplayName("refresh token jti를 TTL과 함께 Redis에 저장한다")
        fun saveRefreshToken() {
            val userId = UserId.newId()

            refreshTokenRedisAdapter.save(refreshToken(userId = userId, tokenId = "refresh-1"))

            assertTrue(refreshTokenRedisAdapter.exists(userId, "refresh-1"))
        }

        @Test
        @DisplayName("이미 만료된 refresh token은 저장하지 않는다")
        fun skipExpiredRefreshToken() {
            val userId = UserId.newId()

            refreshTokenRedisAdapter.save(
                refreshToken(
                    userId = userId,
                    tokenId = "expired-refresh",
                    expiresAt = now.minusSeconds(1)
                )
            )

            assertFalse(refreshTokenRedisAdapter.exists(userId, "expired-refresh"))
        }
    }

    @Nested
    @DisplayName("rotate()")
    inner class Rotate {

        @Test
        @DisplayName("기존 refresh token을 삭제하고 새 refresh token으로 교체한다")
        fun rotateRefreshToken() {
            val userId = UserId.newId()
            refreshTokenRedisAdapter.save(refreshToken(userId = userId, tokenId = "old-refresh"))

            val rotated = refreshTokenRedisAdapter.rotate(
                userId = userId,
                oldTokenId = "old-refresh",
                newToken = refreshToken(userId = userId, tokenId = "new-refresh")
            )

            assertTrue(rotated)
            assertFalse(refreshTokenRedisAdapter.exists(userId, "old-refresh"))
            assertTrue(refreshTokenRedisAdapter.exists(userId, "new-refresh"))
        }

        @Test
        @DisplayName("기존 refresh token이 없으면 새 refresh token을 저장하지 않는다")
        fun rejectRotationWhenOldRefreshTokenDoesNotExist() {
            val userId = UserId.newId()

            val rotated = refreshTokenRedisAdapter.rotate(
                userId = userId,
                oldTokenId = "missing-refresh",
                newToken = refreshToken(userId = userId, tokenId = "new-refresh")
            )

            assertFalse(rotated)
            assertFalse(refreshTokenRedisAdapter.exists(userId, "new-refresh"))
        }
    }

    @Nested
    @DisplayName("revoke()")
    inner class Revoke {

        @Test
        @DisplayName("저장된 refresh token jti를 Redis에서 삭제한다")
        fun revokeRefreshToken() {
            val userId = UserId.newId()
            refreshTokenRedisAdapter.save(refreshToken(userId = userId, tokenId = "refresh-1"))

            refreshTokenRedisAdapter.revoke(userId, "refresh-1")

            assertFalse(refreshTokenRedisAdapter.exists(userId, "refresh-1"))
        }
    }

    private fun refreshToken(
        userId: UserId,
        tokenId: String,
        expiresAt: Instant = now.plus(Duration.ofDays(14))
    ): StoredRefreshToken {
        return StoredRefreshToken(
            id = tokenId,
            userId = userId,
            expiresAt = expiresAt
        )
    }

    companion object {
        private const val REDIS_PORT = 6379

        private val redisContainer = GenericContainer("redis:7-alpine")
            .withExposedPorts(REDIS_PORT)

        private lateinit var connectionFactory: LettuceConnectionFactory

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            redisContainer.start()
            connectionFactory = LettuceConnectionFactory(
                RedisStandaloneConfiguration(
                    redisContainer.host,
                    redisContainer.getMappedPort(REDIS_PORT)
                )
            )
            connectionFactory.afterPropertiesSet()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() {
            connectionFactory.destroy()
            redisContainer.stop()
        }
    }
}
