package me.rgunny.kachi.ai.adapter.outbound.llm

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.config.LlmCircuitBreakerConfig
import me.rgunny.kachi.ai.config.LlmCircuitBreakerProperties
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmBilling
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.fake.NamedLlmProviderPort
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.MutableClock
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@DisplayName("GuardedLlmModel")
class GuardedLlmModelTest {
    private val delegate = NamedLlmProviderPort(MODEL.qualifiedCode)
    private val clock = MutableClock()
    private val providerHolds = ProviderHoldRegistry()

    @Test
    @DisplayName("재시도 가능한 실패가 임계치에 도달하면 다음 호출을 차단한다")
    fun openAfterRetryableFailures() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        repeat(2) { delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }

        repeat(2) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        val blocked = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(MODEL.provider, blocked.failure.provider)
        assertEquals(2, delegate.summarizeStoryCallCount)
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureCode::class, names = [
        "LLM_RATE_LIMITED",
        "LLM_SERVER_ERROR",
        "LLM_NETWORK_ERROR",
        "LLM_UNKNOWN_ERROR"
    ])
    @DisplayName("rate limit·서버 오류·네트워크·미분류 실패도 회로를 여는 근거로 기록한다")
    fun recordEveryRetryableFailure(code: LlmFailureCode) = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        repeat(2) { delegate.failures += AiTestFixture.llmProviderException(code) }

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        // cooldown 경과(rate limit은 쉬는 동안 회로에 닿지 않음)
        clock.advance(DEFAULT_COOLDOWN)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
    }

    @Test
    @DisplayName("응답 계약 위반이 연속돼도 회로가 열리지 않는다")
    fun keepClosedOnInvalidResponse() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        repeat(5) { delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE) }

        repeat(5) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        assertEquals(6, delegate.summarizeStoryCallCount)
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureCode::class, names = [
        "LLM_REQUEST_REJECTED",
        "LLM_MODEL_NOT_FOUND",
        "LLM_UNAUTHORIZED",
        "LLM_PAYMENT_REQUIRED",
        "LLM_FORBIDDEN"
    ])
    @DisplayName("요청 거부와 한 건으로 확정되는 실패는 회로를 열지 않는다")
    fun keepClosedOnNonTransientFailure(code: LlmFailureCode) = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.failures += AiTestFixture.llmProviderException(code)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        assertEquals(0, circuitBreaker.metrics.numberOfFailedCalls)
    }

    @Test
    @DisplayName("모델이 없다는 응답을 받으면 재탐색 시각까지 그 모델을 호출하지 않는다")
    fun holdModelOnModelNotFound() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        val until = AiTestFixture.NOW.plus(REPROBE_AFTER)
        assertEquals("model-hold(code=LLM_MODEL_NOT_FOUND, until=$until)", provider.exclusionReason(clock.instant()))
        assertBlocked(provider, expectedCalls = 1)
        clock.advance(REPROBE_AFTER.minusSeconds(1))
        assertBlocked(provider, expectedCalls = 1)
    }

    @Test
    @DisplayName("재탐색 시각이 지나면 한 번 호출이 나가고 같은 실패면 다시 보류한다")
    fun reprobeModelAfterHoldAndHoldAgainOnSameFailure() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        clock.advance(REPROBE_AFTER)
        val reprobe = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_MODEL_NOT_FOUND, reprobe.failure.code)
        assertEquals(2, delegate.summarizeStoryCallCount)
        assertEquals(clock.instant().plus(REPROBE_AFTER), provider.status(clock.instant()).hold?.until)
        assertBlocked(provider, expectedCalls = 2)
    }

    @Test
    @DisplayName("재탐색 호출이 성공하면 보류가 남지 않는다")
    fun reprobeSuccessClearsHold() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        clock.advance(REPROBE_AFTER)
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(3, delegate.summarizeStoryCallCount)
        assertNull(provider.status(clock.instant()).hold)
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureCode::class, names = ["LLM_UNAUTHORIZED", "LLM_PAYMENT_REQUIRED", "LLM_FORBIDDEN"])
    @DisplayName("계정 실패는 같은 제공자의 다른 모델까지 재탐색 시각까지 막고 다른 제공자는 막지 않는다")
    fun holdProviderOnAccountFailure(code: LlmFailureCode) = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        // 같은 제공자의 다른 가드(같은 모델 상수, 위임 대상과 회로만 별도)
        val siblingDelegate = NamedLlmProviderPort("${MODEL.qualifiedCode}#sibling", MODEL.provider)
        val sibling = guarded(delegate = siblingDelegate, circuitBreaker = wideCircuitBreaker())
        val otherDelegate = NamedLlmProviderPort(OTHER_PROVIDER_MODEL.qualifiedCode, OTHER_PROVIDER_MODEL.provider)
        val other = guarded(delegate = otherDelegate, model = OTHER_PROVIDER_MODEL, circuitBreaker = wideCircuitBreaker(OTHER_PROVIDER_MODEL))
        delegate.failures += AiTestFixture.llmProviderException(code)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        val until = AiTestFixture.NOW.plus(REPROBE_AFTER)
        val reason = "provider-hold(code=${code.code}, until=$until)"
        assertEquals(reason, provider.exclusionReason(clock.instant()))
        assertEquals(reason, sibling.exclusionReason(clock.instant()))
        val blocked = assertFailsWith<LlmProviderException> { sibling.summarizeStory(KEYWORDS, null, ARTICLES) }
        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(0, siblingDelegate.summarizeStoryCallCount)
        other.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(1, otherDelegate.summarizeStoryCallCount)

        clock.advance(REPROBE_AFTER)
        sibling.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(1, siblingDelegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("제공자 보류는 더 늦은 시각으로만 갱신된다")
    fun extendProviderHoldOnlyForward() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_PAYMENT_REQUIRED)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        val first = provider.status(clock.instant()).hold

        providerHolds.hold(MODEL.provider, LlmFailureCode.LLM_FORBIDDEN, AiTestFixture.NOW.plusSeconds(1))

        assertEquals(first, provider.status(clock.instant()).hold)
    }

    @Test
    @DisplayName("보류 중 차단은 회로 집계에 들어가지 않는다")
    fun holdBlockIsNotRecorded() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        val bufferedCalls = circuitBreaker.metrics.numberOfBufferedCalls

        repeat(3) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }

        assertEquals(bufferedCalls, circuitBreaker.metrics.numberOfBufferedCalls)
        assertEquals(0, circuitBreaker.metrics.numberOfNotPermittedCalls)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("상태 스냅샷의 보류는 제공자 보류를 모델 보류보다 우선한다")
    fun statusPrefersProviderHold() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        clock.advance(Duration.ofMinutes(1))
        providerHolds.hold(MODEL.provider, LlmFailureCode.LLM_FORBIDDEN, clock.instant().plus(REPROBE_AFTER))

        val hold = assertNotNull(provider.status(clock.instant()).hold)

        assertEquals(LlmFailureCode.LLM_FORBIDDEN, hold.code)
        assertEquals(clock.instant().plus(REPROBE_AFTER), hold.until)
    }

    @Test
    @DisplayName("되돌리면 모델 보류와 제공자 보류도 풀려 호출이 다시 나간다")
    fun resetClearsHolds() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_MODEL_NOT_FOUND)
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_PAYMENT_REQUIRED)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        provider.reset()
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        assertNotNull(providerHolds.holdOf(MODEL.provider, clock.instant()))

        provider.reset()
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertNull(provider.status(clock.instant()).hold)
        assertNull(providerHolds.holdOf(MODEL.provider, clock.instant()))
        assertEquals(3, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("LLM 실패가 아닌 예외는 기록하지 않고 그대로 전파한다")
    fun propagateNonLlmException() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        repeat(5) { delegate.failures += IllegalStateException("boom") }

        repeat(5) {
            assertFailsWith<IllegalStateException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("성공하면 위임 대상의 결과를 그대로 돌려준다")
    fun returnDelegateResult() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)

        val result = provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(MODEL.qualifiedCode, result.metadata.model)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("실패율이 임계치 미만이면 열리지 않는다")
    fun keepClosedBelowFailureRateThreshold() = runBlocking {
        val circuitBreaker = circuitBreaker(slidingWindowSize = 4, minimumNumberOfCalls = 4)
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        repeat(3) { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("차단된 호출은 회로 집계에 들어가지 않는다")
    fun blockedCallsAreNotCounted() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        repeat(2) { delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }
        repeat(2) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        val failedCalls = circuitBreaker.metrics.numberOfFailedCalls

        repeat(3) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }

        assertEquals(3, circuitBreaker.metrics.numberOfNotPermittedCalls)
        assertEquals(failedCalls, circuitBreaker.metrics.numberOfFailedCalls)
    }

    @Test
    @DisplayName("half-open에서는 허용한 수만큼만 호출을 통과시킨다")
    fun permitOnlyConfiguredProbes() = runBlocking {
        val circuitBreaker = circuitBreaker(permittedNumberOfCallsInHalfOpenState = 2)
        val provider = guarded(circuitBreaker = circuitBreaker)
        openCircuit(provider)
        circuitBreaker.transitionToHalfOpenState()
        repeat(2) { delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }
        val callsBeforeProbe = delegate.summarizeStoryCallCount

        repeat(2) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        val blocked = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(2, delegate.summarizeStoryCallCount - callsBeforeProbe)
        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
    }

    @Test
    @DisplayName("half-open 허용 수를 넘어 동시에 들어온 호출은 사유 없이 차단된다")
    fun blockConcurrentProbeBeyondPermittedCalls() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        openCircuit(provider)
        circuitBreaker.transitionToHalfOpenState()
        val gate = CompletableDeferred<Unit>()
        delegate.gate = gate

        val callsBefore = delegate.summarizeStoryCallCount

        val probe = async { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        while (delegate.summarizeStoryCallCount == callsBefore) {
            yield()
        }
        val blocked = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        gate.complete(Unit)
        probe.await()

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(1, delegate.summarizeStoryCallCount - callsBefore)
    }

    @Test
    @DisplayName("half-open 호출이 성공하면 회로가 닫힌다")
    fun closeAfterSuccessfulProbe() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        openCircuit(provider)
        circuitBreaker.transitionToHalfOpenState()

        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("half-open 호출이 재시도 가능한 실패면 회로가 다시 열린다")
    fun reopenAfterFailedProbe() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        openCircuit(provider)
        circuitBreaker.transitionToHalfOpenState()
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
    }

    @Test
    @DisplayName("half-open 호출이 입력 탓 실패면 모델은 살아 있다고 보고 닫는다")
    fun closeWhenProbeFailsWithInputFailure() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        openCircuit(provider)
        circuitBreaker.transitionToHalfOpenState()
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("rate limit이 지시한 시간 동안 호출하지 않는다")
    fun holdCallsDuringRetryAfter() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        assertBlocked(provider, expectedCalls = 1)

        clock.advance(Duration.ofSeconds(29))
        assertBlocked(provider, expectedCalls = 1)

        clock.advance(Duration.ofSeconds(1))
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(2, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("Retry-After가 없는 rate limit은 기본 cooldown을 적용한다")
    fun holdCallsWithDefaultCooldown() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = null)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        clock.advance(DEFAULT_COOLDOWN.minusSeconds(1))
        assertBlocked(provider, expectedCalls = 1)

        clock.advance(Duration.ofSeconds(1))
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(2, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("Retry-After가 상한을 넘으면 상한까지만 기다린다")
    fun capCooldownAtMaximum() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = MAX_COOLDOWN.plusHours(1).toMillis())

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        clock.advance(MAX_COOLDOWN.minusSeconds(1))
        assertBlocked(provider, expectedCalls = 1)

        clock.advance(Duration.ofSeconds(1))
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(2, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("cooldown 차단은 회로 집계에 들어가지 않는다")
    fun cooldownBlockIsNotRecorded() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        val bufferedCalls = circuitBreaker.metrics.numberOfBufferedCalls

        repeat(3) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }

        assertEquals(bufferedCalls, circuitBreaker.metrics.numberOfBufferedCalls)
        assertEquals(0, circuitBreaker.metrics.numberOfNotPermittedCalls)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
    }

    @Test
    @DisplayName("cooldown이 끝나면 다시 정상 호출된다")
    fun resumeAfterCooldown() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        clock.advance(Duration.ofSeconds(30))
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(3, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("rate limit이 아닌 실패는 cooldown을 걸지 않는다")
    fun doNotHoldOnOtherFailures() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(2, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("동시에 들어온 rate limit은 더 늦은 시각으로만 cooldown을 갱신한다")
    fun extendCooldownOnlyForward() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        val gate = CompletableDeferred<Unit>()
        delegate.gate = gate
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 60_000)
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 10_000)

        val first = async { runCatching { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        val second = async { runCatching { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        while (delegate.summarizeStoryCallCount < 2) {
            yield()
        }
        gate.complete(Unit)
        first.await()
        second.await()

        clock.advance(Duration.ofSeconds(59))
        assertEquals(
            "cooldown(until=${AiTestFixture.NOW.plusSeconds(60)})",
            provider.exclusionReason(clock.instant())
        )
    }

    @Test
    @DisplayName("회로가 닫혀 있고 쉬는 중이 아니면 호출 가능하다고 본다")
    fun availableWhenClosed() {
        assertTrue(guarded().isLikelyAvailable(clock.instant()))
    }

    @Test
    @DisplayName("회로가 열려 있으면 호출 가능하지 않다고 본다")
    fun unavailableWhenOpen() = runBlocking {
        val provider = guarded()
        openCircuit(provider)

        assertFalse(provider.isLikelyAvailable(clock.instant()))
    }

    @Test
    @DisplayName("쉬는 중이면 호출 가능하지 않다고 보고 끝나면 다시 가능하다고 본다")
    fun unavailableWhileCoolingDown() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertFalse(provider.isLikelyAvailable(clock.instant()))

        clock.advance(Duration.ofSeconds(30))
        assertTrue(provider.isLikelyAvailable(clock.instant()))
    }

    @Test
    @DisplayName("half-open은 호출 가능하다고 본다")
    fun availableWhenHalfOpen() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        openCircuit(provider)
        circuitBreaker.transitionToHalfOpenState()

        assertTrue(provider.isLikelyAvailable(clock.instant()))
    }

    @Test
    @DisplayName("차단 실패는 자기 provider 이름을 가진 NOT_PERMITTED다")
    fun unavailableFailureShape() = runBlocking {
        val provider = guarded()
        openCircuit(provider)

        val blocked = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(MODEL.provider, blocked.failure.provider)
        assertNull(blocked.failure.statusCode)
        assertNull(blocked.failure.retryAfterMillis)
    }

    @Test
    @DisplayName("키워드 확장도 같은 차단을 받는다")
    fun expandKeywordIsGuarded() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        repeat(2) { delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }

        repeat(2) { assertFailsWith<LlmProviderException> { provider.expandKeyword(KEYWORD, 3) } }
        val blocked = assertFailsWith<LlmProviderException> { provider.expandKeyword(KEYWORD, 3) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(2, delegate.expandCallCount)
    }

    @Test
    @DisplayName("실패 예외는 분류를 잃지 않고 그대로 전파된다")
    fun propagateSameFailureInstance() = runBlocking {
        val provider = guarded()
        val thrown = AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)
        delegate.failures += thrown

        val caught = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertSame(thrown, caught)
    }

    @Test
    @DisplayName("coroutine 취소는 회로 집계에 들어가지 않는다")
    fun cancellationIsNotCounted() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.failures += CancellationException("cancelled")

        assertFailsWith<CancellationException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(0, circuitBreaker.metrics.numberOfSuccessfulCalls)
        assertEquals(0, circuitBreaker.metrics.numberOfFailedCalls)
        assertEquals(0, circuitBreaker.metrics.numberOfBufferedCalls)

        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(2, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("요약 준비의 plan은 위임 대상의 plan이다")
    fun prepareReturnsDelegatePlan() {
        val provider = guarded()

        assertEquals(delegate.prepareStorySummary().plan, provider.prepareStorySummary().plan)
    }

    @Test
    @DisplayName("회로가 열려 있어도 요약 준비는 실패하지 않는다")
    fun prepareDoesNotThrowWhenOpen() = runBlocking {
        val provider = guarded()
        openCircuit(provider)

        assertEquals(MODEL.provider, provider.prepareStorySummary().plan.provider)
    }

    @Test
    @DisplayName("요약 준비가 돌려준 실행 단위도 같은 차단을 받는다")
    fun preparedSummaryIsGuarded() = runBlocking {
        val provider = guarded()
        val prepared = provider.prepareStorySummary()
        openCircuit(provider)
        val callsBefore = delegate.summarizeStoryCallCount

        val blocked = assertFailsWith<LlmProviderException> { prepared.summarize(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(callsBefore, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("차단이 없으면 요약 준비가 돌려준 실행 단위가 위임 대상의 결과를 준다")
    fun preparedSummaryDelegatesWhenNotBlocked() = runBlocking {
        val provider = guarded()

        val result = provider.prepareStorySummary().summarize(KEYWORDS, null, ARTICLES)

        assertEquals(MODEL.qualifiedCode, result.metadata.model)
        assertEquals(1, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("강제로 열어 둔 회로도 호출을 차단하고 사유를 남긴다")
    fun blockWhenCircuitIsForcedOpen() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        circuitBreaker.transitionToForcedOpenState()

        val blocked = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(0, delegate.summarizeStoryCallCount)
        assertEquals("forced-open", provider.exclusionReason(clock.instant()))
        assertFalse(provider.isLikelyAvailable(clock.instant()))
    }

    @Test
    @DisplayName("회로 상태 전이에 listener가 등록돼 있다")
    fun registerStateTransitionListener() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        val transitions = mutableListOf<Pair<CircuitBreaker.State, CircuitBreaker.State>>()
        circuitBreaker.eventPublisher.onStateTransition {
            transitions += it.stateTransition.fromState to it.stateTransition.toState
        }

        openCircuit(provider)

        assertEquals(listOf(CircuitBreaker.State.CLOSED to CircuitBreaker.State.OPEN), transitions)
    }

    @Test
    @DisplayName("제외 사유는 상태별 문자열을 준다")
    fun exclusionReasonPerState() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())

        assertNull(provider.exclusionReason(clock.instant()))

        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }
        assertEquals(
            "cooldown(until=${AiTestFixture.NOW.plusSeconds(30)})",
            provider.exclusionReason(clock.instant())
        )

        clock.advance(Duration.ofSeconds(30))
        val openDelegate = NamedLlmProviderPort(MODEL.qualifiedCode)
        val opened = guarded(delegate = openDelegate)
        openCircuit(opened, openDelegate)
        assertEquals("open", opened.exclusionReason(clock.instant()))
    }

    @Test
    @DisplayName("느린 호출 비율이 임계치를 넘으면 회로를 연다")
    fun openOnSlowCallRate() = runBlocking {
        val circuitBreaker = circuitBreaker(
            slowCallDurationThreshold = Duration.ofMillis(10),
            slowCallRateThreshold = 50f
        )
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.callDelay = Duration.ofMillis(30)

        repeat(2) { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
        assertEquals(2, circuitBreaker.metrics.numberOfSlowCalls)
    }

    @Test
    @DisplayName("느린 호출 비율 임계치가 100이면 모든 호출이 느릴 때만 연다")
    fun keepClosedUntilEveryCallIsSlow() = runBlocking {
        val circuitBreaker = circuitBreaker(
            slowCallDurationThreshold = Duration.ofMillis(10),
            slowCallRateThreshold = 100f
        )
        val provider = guarded(circuitBreaker = circuitBreaker)

        delegate.callDelay = Duration.ofMillis(30)
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        delegate.callDelay = Duration.ZERO
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        assertEquals(1, circuitBreaker.metrics.numberOfSlowCalls)
    }

    @Test
    @DisplayName("느린 호출도 결과는 그대로 돌려준다")
    fun slowCallStillReturnsResult() = runBlocking {
        val circuitBreaker = circuitBreaker(
            slowCallDurationThreshold = Duration.ofMillis(10),
            slowCallRateThreshold = 50f
        )
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.callDelay = Duration.ofMillis(30)

        val result = provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(MODEL.qualifiedCode, result.metadata.model)
    }

    @Test
    @DisplayName("상태 스냅샷은 회로 상태와 쉬는 시각, 집계를 함께 담는다")
    fun statusSnapshot() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        provider.summarizeStory(KEYWORDS, null, ARTICLES)
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        val status = provider.status(clock.instant())

        assertEquals(MODEL, status.model)
        assertEquals(CircuitBreaker.State.CLOSED.name, status.circuitBreakerState)
        assertEquals(AiTestFixture.NOW.plusSeconds(30), status.cooldownUntil)
        assertEquals(1, status.failedCalls)
        assertEquals(1, status.successfulCalls)
        assertEquals(2, status.bufferedCalls)
    }

    @Test
    @DisplayName("쉬는 시간이 지나면 상태 스냅샷에서 쉬는 시각이 빠진다")
    fun statusDropsExpiredCooldown() = runBlocking {
        val provider = guarded(circuitBreaker = wideCircuitBreaker())
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        clock.advance(Duration.ofSeconds(30))

        assertNull(provider.status(clock.instant()).cooldownUntil)
    }

    @Test
    @DisplayName("되돌리면 열린 회로가 닫히고 쉬는 시각도 지워져 호출이 다시 나간다")
    fun resetClearsCircuitAndCooldown() = runBlocking {
        val circuitBreaker = circuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        // 회로 open과 cooldown이 동시에 걸리는 실패 순서(마지막이 rate limit)
        delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        repeat(2) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
        val callsBefore = delegate.summarizeStoryCallCount

        provider.reset()
        provider.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        assertNull(provider.status(clock.instant()).cooldownUntil)
        assertEquals(callsBefore + 1, delegate.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("이미 닫혀 있는 회로를 되돌려도 실패하지 않고 쉬는 시각만 지운다")
    fun resetClosedCircuit() = runBlocking {
        val circuitBreaker = wideCircuitBreaker()
        val provider = guarded(circuitBreaker = circuitBreaker)
        delegate.failures += AiTestFixture.rateLimitedException(retryAfterMillis = 30_000)
        assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        provider.reset()

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        assertNull(provider.exclusionReason(clock.instant()))
    }

    private suspend fun openCircuit(
        provider: GuardedLlmModel,
        delegate: NamedLlmProviderPort = this.delegate
    ) {
        repeat(2) { delegate.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }
        repeat(2) { assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) } }
    }

    /** 실패율로는 열리지 않는 회로를 만든다. */
    private fun wideCircuitBreaker(model: LlmModel = MODEL): CircuitBreaker =
        circuitBreaker(slidingWindowSize = 8, minimumNumberOfCalls = 8, model = model)

    private suspend fun assertBlocked(provider: GuardedLlmModel, expectedCalls: Int) {
        val blocked = assertFailsWith<LlmProviderException> { provider.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, blocked.failure.code)
        assertEquals(expectedCalls, delegate.summarizeStoryCallCount)
    }

    private fun guarded(
        delegate: NamedLlmProviderPort = this.delegate,
        model: LlmModel = MODEL,
        circuitBreaker: CircuitBreaker = circuitBreaker()
    ): GuardedLlmModel {
        return GuardedLlmModel(
            delegate = delegate,
            model = model,
            billing = LlmBilling.FREE_TIER,
            circuitBreaker = circuitBreaker,
            cooldown = LlmCooldownSettings(default = DEFAULT_COOLDOWN, max = MAX_COOLDOWN),
            hold = AiTestFixture.holdSettings(REPROBE_AFTER),
            providerHolds = providerHolds,
            clock = clock
        )
    }

    /**
     * `LlmCircuitBreakerConfig`로 서킷 브레이커를 만들되 창을 좁혀 두 번의 실패로 열리게 한다.
     * open 대기는 길게 잡아 시간이 아니라 명시적 전이로만 half-open이 되게 한다.
     */
    private fun circuitBreaker(
        slidingWindowSize: Int = 2,
        minimumNumberOfCalls: Int = 2,
        slowCallDurationThreshold: Duration = Duration.ofSeconds(8),
        slowCallRateThreshold: Float = 100f,
        permittedNumberOfCallsInHalfOpenState: Int = 1,
        model: LlmModel = MODEL
    ): CircuitBreaker {
        val config = LlmCircuitBreakerConfig().circuitBreakerConfig(
            LlmCircuitBreakerProperties(
                slidingWindowSize = slidingWindowSize,
                minimumNumberOfCalls = minimumNumberOfCalls,
                failureRateThreshold = 50f,
                slowCallRateThreshold = slowCallRateThreshold,
                waitDurationInOpenState = Duration.ofHours(1),
                permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState
            ),
            slowCallDurationThreshold = slowCallDurationThreshold
        )

        return CircuitBreakerRegistry.of(config).circuitBreaker(model.qualifiedCode)
    }

    private companion object {
        val MODEL: LlmModel = AiTestFixture.LLM_MODEL
        val OTHER_PROVIDER_MODEL: LlmModel = LlmModel.entries.first { it.provider != MODEL.provider }
        val REPROBE_AFTER: Duration = Duration.ofHours(1)
        val KEYWORD: AiKeyword = AiTestFixture.keyword()
        val KEYWORDS: List<AiKeyword> = listOf(KEYWORD)
        val ARTICLES = listOf(AiTestFixture.storySummaryArticle())
        val DEFAULT_COOLDOWN: Duration = Duration.ofSeconds(60)
        val MAX_COOLDOWN: Duration = Duration.ofMinutes(10)
    }
}
