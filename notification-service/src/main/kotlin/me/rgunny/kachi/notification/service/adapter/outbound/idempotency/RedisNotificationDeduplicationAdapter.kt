package me.rgunny.kachi.notification.service.adapter.outbound.idempotency

import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.notification.application.port.outbound.idempotency.NotificationDeduplicationPort
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis SET NX 기반 notification 멱등 마커 adapter.
 *
 * Redis는 동시 요청/동시 consume을 줄이는 1차 필터다.
 * 최종 정합성은 requestId unique index와 notification/outbox CAS가 보장한다.
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
