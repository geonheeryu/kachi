package me.rgunny.kachi.collector.domain.outbox

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("CollectorOutboxRetryPolicy")
class CollectorOutboxRetryPolicyTest {
    private val baseDelay = Duration.ofSeconds(1)
    private val maxDelay = Duration.ofMinutes(1)

    // 지수 증가와 cap을 보는 테스트는 분산값 없이 확인한다.
    private val policy = policy(jitterRatio = 0.0)

    @Test
    @DisplayName("시도 횟수에 따라 backoff가 지수로 늘어난다")
    fun growBackoffExponentially() {
        assertEquals(Duration.ofSeconds(1), policy.backoff(1))
        assertEquals(Duration.ofSeconds(2), policy.backoff(2))
        assertEquals(Duration.ofSeconds(4), policy.backoff(3))
        assertEquals(Duration.ofSeconds(8), policy.backoff(4))
    }

    @Test
    @DisplayName("backoff는 maxDelay를 넘지 않는다")
    fun capBackoffAtMaxDelay() {
        assertEquals(maxDelay, policy.backoff(7))
    }

    @Test
    @DisplayName("시도 횟수가 아주 커도 overflow 없이 maxDelay를 돌려준다")
    fun capBackoffWithoutOverflow() {
        assertEquals(maxDelay, policy.backoff(100))
        assertEquals(maxDelay, policy.backoff(Int.MAX_VALUE))
    }

    @Test
    @DisplayName("jitter는 계산된 대기 시간을 ± 비율 범위 안에서 분산한다")
    fun spreadBackoffWithinJitterRange() {
        val jittered = policy(jitterRatio = 0.2, random = Random(SEED))

        // baseDelay 1s, ratio 0.2 -> 800ms ~ 1200ms
        repeat(50) {
            val delay = jittered.backoff(1).toMillis()
            assertTrue(delay in 800..1200, "delay=$delay")
        }
    }

    @Test
    @DisplayName("jitter를 주면 같은 시도 횟수라도 대기 시간이 매번 같지 않다")
    fun varyBackoffAcrossCalls() {
        val jittered = policy(jitterRatio = 0.2, random = Random(SEED))

        val delays = (1..50).map { jittered.backoff(1) }.toSet()

        assertTrue(delays.size > 1, "jitter가 적용되면 대기 시간이 한 값에 고정되지 않는다: $delays")
    }

    @Test
    @DisplayName("jitter 비율이 0이면 계산값을 그대로 쓴다")
    fun keepBackoffWhenJitterIsDisabled() {
        val delays = (1..10).map { policy.backoff(2) }.toSet()

        assertEquals(setOf(Duration.ofSeconds(2)), delays)
    }

    @Test
    @DisplayName("시도 횟수가 한도에 도달하면 소진으로 판단한다")
    fun exhaustedWhenAttemptsReachMax() {
        assertFalse(policy.exhausted(4))
        assertTrue(policy.exhausted(5))
        assertTrue(policy.exhausted(6))
    }

    @Test
    @DisplayName("정책 값이 유효하지 않으면 만들 수 없다")
    fun rejectInvalidPolicyValues() {
        assertFailsWith<IllegalArgumentException> {
            CollectorOutboxRetryPolicy(0, baseDelay, maxDelay)
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorOutboxRetryPolicy(5, Duration.ZERO, maxDelay)
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorOutboxRetryPolicy(5, baseDelay, Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorOutboxRetryPolicy(5, maxDelay, baseDelay)
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorOutboxRetryPolicy(5, baseDelay, maxDelay, multiplier = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorOutboxRetryPolicy(5, baseDelay, maxDelay, jitterRatio = 1.5)
        }
    }

    @Test
    @DisplayName("시도한 적 없으면 backoff를 계산하지 않는다")
    fun rejectBackoffBeforeFirstAttempt() {
        assertFailsWith<IllegalArgumentException> { policy.backoff(0) }
    }

    private fun policy(
        jitterRatio: Double,
        random: Random = Random.Default
    ): CollectorOutboxRetryPolicy {
        return CollectorOutboxRetryPolicy(
            maxAttempts = 5,
            baseDelay = baseDelay,
            maxDelay = maxDelay,
            multiplier = 2.0,
            jitterRatio = jitterRatio,
            random = random
        )
    }

    private companion object {
        const val SEED = 42
    }
}
