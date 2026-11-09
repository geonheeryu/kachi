package me.rgunny.kachi.notification.worker.adapter.outbound.idempotency

import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.notification.application.port.outbound.idempotency.NotificationDeduplicationPort
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * worker dispatch 중복 처리를 줄이는 Redis SET NX adapter.
 *
 * Redis 마커는 1차 가드, 최종 정합성은 Mongo 상태 claim이 보장한다.
 */
@Component
class RedisNotificationDeduplicationAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : NotificationDeduplicationPort {

    override suspend fun acquire(key: String, ttl: Duration): Boolean {
        require(key.isNotBlank()) { "key must not be blank" }
        require(!ttl.isZero && !ttl.isNegative) { "ttl must be positive" }

        return redisTemplate.opsForValue()
            .setIfAbsent(key, MARKER_VALUE, ttl)
            .awaitSingle()
    }

    override suspend fun release(key: String) {
        require(key.isNotBlank()) { "key must not be blank" }

        redisTemplate.delete(key).awaitSingle()
    }

    private companion object {
        const val MARKER_VALUE = "1"
    }
}
