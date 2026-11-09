package me.rgunny.kachi.ai.adapter.outbound.llm

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import kotlinx.coroutines.CancellationException
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmHold
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmKeywordExpansionResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmModelStatus
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmBilling
import me.rgunny.kachi.ai.domain.llm.LlmFailure
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmModel
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 모델 하나의 차단 상태를 두고 [LlmProviderPort] 위임 호출을 거르는 데코레이터.
 *
 * 차단 장치는 서킷 브레이커, rate limit cooldown, 모델 hold(404), 제공자 hold(401·402·403) 넷이다.
 * 제공자 hold는 같은 제공자의 가드들이 [providerHolds]로 공유한다.
 * 하나라도 걸려 있으면 위임 대상을 호출하지 않고 [LlmFailureCode.LLM_NOT_PERMITTED]로 실패한다.
 * hold는 한 건으로 걸리고 대기 시각이 지나면 호출 한 번이 통과한다.
 * 재시도는 하지 않는다.
 */
class GuardedLlmModel(
    private val delegate: LlmProviderPort,
    override val model: LlmModel,
    val billing: LlmBilling,
    private val circuitBreaker: CircuitBreaker,
    private val cooldown: LlmCooldownSettings,
    private val hold: LlmHoldSettings,
    private val providerHolds: ProviderHoldRegistry,
    private val clock: Clock
) : LlmProviderCandidate {

    /**
     * rate limit cooldown 종료 시각.
     *
     * 걸려 있지 않으면 null이다.
     */
    private val cooldownUntil = AtomicReference<Instant?>(null)
    private val modelHold = AtomicReference<LlmHold?>(null)

    init {
        // 서킷 상태 전이 로그(OPEN은 warn)
        circuitBreaker.eventPublisher.onStateTransition { event ->
            val transition = "${event.stateTransition.fromState} -> ${event.stateTransition.toState}"

            if (event.stateTransition.toState == CircuitBreaker.State.OPEN) {
                log.warn("LLM model circuit breaker opened: model={}, transition={}", model.qualifiedCode, transition)
            } else {
                log.info("LLM model circuit breaker changed: model={}, transition={}", model.qualifiedCode, transition)
            }
        }
    }

    /**
     * 위임 대상의 plan을 들고 호출은 이 가드를 거치는 실행 단위를 돌려준다.
     */
    override fun prepareStorySummary(): PreparedLlmStorySummary {
        return GuardedPreparedStorySummary(plan = delegate.prepareStorySummary().plan, model = this)
    }

    override suspend fun expandKeyword(
        keyword: AiKeyword,
        maxExpansions: Int
    ): LlmKeywordExpansionResult {
        return guarded { delegate.expandKeyword(keyword, maxExpansions) }
    }

    override suspend fun summarizeStory(
        keywords: List<AiKeyword>,
        previousSummary: PreviousStorySummary?,
        articles: List<StorySummaryArticle>
    ): LlmStorySummaryResult {
        return guarded { delegate.summarizeStory(keywords, previousSummary, articles) }
    }

    /**
     * [exclusionReason]으로 본 호출 가능 여부.
     *
     * 호출 시점의 permission 판정과 다를 수 있다.
     */
    override fun isLikelyAvailable(now: Instant): Boolean = exclusionReason(now) == null

    /**
     * 지금 이 모델이 어떤 상태인지의 스냅샷.
     *
     * cooldown 종료 시각과 hold는 걸려 있을 때만 담고, 아니면 null이다.
     */
    fun status(now: Instant): LlmModelStatus {
        val metrics = circuitBreaker.metrics

        return LlmModelStatus(
            model = model,
            billing = billing,
            circuitBreakerState = circuitBreaker.state.name,
            cooldownUntil = coolingDownUntil(now),
            hold = activeHold(now),
            failureRate = metrics.failureRate,
            slowCallRate = metrics.slowCallRate,
            bufferedCalls = metrics.numberOfBufferedCalls,
            successfulCalls = metrics.numberOfSuccessfulCalls,
            failedCalls = metrics.numberOfFailedCalls,
            notPermittedCalls = metrics.numberOfNotPermittedCalls
        )
    }

    /**
     * 서킷·cooldown·모델 hold·제공자 hold를 전부 푼다.
     *
     * 이미 닫힌 회로는 전이하지 않는다(Resilience4j는 CLOSED -> CLOSED 전이 요청을 거부함).
     */
    fun reset() {
        if (circuitBreaker.state != CircuitBreaker.State.CLOSED) {
            circuitBreaker.transitionToClosedState()
        }
        cooldownUntil.set(null)
        modelHold.set(null)
        providerHolds.release(model.provider)
    }

    /**
     * 후보에서 빠질 이유.
     *
     * 빠질 이유가 없으면 null이다.
     */
    fun exclusionReason(now: Instant): String? {
        providerHolds.holdOf(model.provider, now)?.let { return providerHoldReason(it) }
        activeModelHold(now)?.let { return modelHoldReason(it) }
        coolingDownUntil(now)?.let { return cooldownReason(it) }

        return when (circuitBreaker.state) {
            CircuitBreaker.State.OPEN -> "open"
            CircuitBreaker.State.FORCED_OPEN -> "forced-open"
            else -> null
        }
    }

    /**
     * 호출되지 못한 이유.
     *
     * 상태로 특정되지 않으면(half-open의 probe 초과) [NOT_PERMITTED]다.
     */
    override fun blockedReason(now: Instant): String = exclusionReason(now) ?: NOT_PERMITTED

    /**
     * 호출을 hold·cooldown 판정과 서킷 브레이커 집계로 감싼다.
     *
     * 취소는 집계에서 빼고 permission을 돌려준다(Resilience4j wrapper는 취소를 성공으로 셈).
     */
    private suspend fun <T : Any> guarded(call: suspend () -> T): T {
        val now = Instant.now(clock)

        // 1. hold와 cooldown은 회로에 닿기 전에 막는다. 이 차단은 집계에 넣지 않는다.
        providerHolds.holdOf(model.provider, now)?.let { throw notPermitted(providerHoldReason(it)) }
        activeModelHold(now)?.let { throw notPermitted(modelHoldReason(it)) }
        coolingDownUntil(now)?.let { throw notPermitted(cooldownReason(it)) }

        // 2. permission을 얻는다(OPEN -> HALF_OPEN 전이 계기).
        if (!circuitBreaker.tryAcquirePermission()) {
            throw notPermitted(blockedReason(Instant.now(clock)))
        }

        // 3. 호출하고 걸린 시간과 함께 결과를 집계한다. 무엇을 실패로 셀지는 회로 설정의 predicate가 가른다.
        val start = circuitBreaker.currentTimestamp
        val timestampUnit = circuitBreaker.timestampUnit

        return try {
            val result = call()
            circuitBreaker.onResult(circuitBreaker.currentTimestamp - start, timestampUnit, result)
            result
        } catch (cancellation: CancellationException) {
            circuitBreaker.releasePermission()
            throw cancellation
        } catch (failure: LlmProviderException) {
            circuitBreaker.onError(circuitBreaker.currentTimestamp - start, timestampUnit, failure)
            holdIfRateLimited(failure.failure)
            holdIfBroken(failure.failure)
            throw failure
        } catch (error: Throwable) {
            circuitBreaker.onError(circuitBreaker.currentTimestamp - start, timestampUnit, error)
            throw error
        }
    }

    private fun coolingDownUntil(now: Instant): Instant? = cooldownUntil.get()?.takeIf { now < it }

    private fun activeModelHold(now: Instant): LlmHold? = modelHold.get()?.takeIf { it.isActive(now) }

    private fun activeHold(now: Instant): LlmHold? = providerHolds.holdOf(model.provider, now) ?: activeModelHold(now)

    private fun cooldownReason(until: Instant): String = "cooldown(until=$until)"

    private fun modelHoldReason(hold: LlmHold): String = "model-hold(code=${hold.code.code}, until=${hold.until})"

    private fun providerHoldReason(hold: LlmHold): String = "provider-hold(code=${hold.code.code}, until=${hold.until})"

    /**
     * rate limit 응답을 받으면 제공자가 지시한 시간만큼 쉰다.
     *
     * 헤더가 없으면 [cooldown]의 기본값, 있으면 최대치까지 지시한 시간이다.
     * 이미 쉬는 중이면 더 늦은 시각만 반영한다.
     */
    private fun holdIfRateLimited(failure: LlmFailure) {
        if (failure.code != LlmFailureCode.LLM_RATE_LIMITED) {
            return
        }

        val duration = failure.retryAfterMillis
            ?.let { minOf(Duration.ofMillis(it), cooldown.max) }
            ?: cooldown.default
        val until = Instant.now(clock).plus(duration)
        val applied = cooldownUntil.updateAndGet { current ->
            if (current == null || until.isAfter(current)) until else current
        }

        log.warn(
            "LLM model is cooling down after rate limit: model={}, until={}, source={}",
            model.qualifiedCode,
            applied,
            if (failure.retryAfterMillis == null) "default" else "retry-after"
        )
    }

    /**
     * 한 건으로 확정되는 구성상 장애는 재탐색 시각까지 후보에서 뺀다.
     *
     * 모델 탓이면 이 모델만, 제공자 탓이면 그 제공자의 모든 모델을 뺀다. 갱신은 더 늦은 시각만 반영한다.
     */
    private fun holdIfBroken(failure: LlmFailure) {
        val until = Instant.now(clock).plus(hold.reprobeAfter)

        if (failure.holdsModel) {
            val applied = modelHold.updateAndGet { current ->
                if (current == null || until.isAfter(current.until)) LlmHold(failure.code, until) else current
            }
            log.warn("LLM model is on hold: model={}, code={}, until={}", model.qualifiedCode, failure.code.code, applied?.until)
        }
        if (failure.holdsProvider) {
            val applied = providerHolds.hold(model.provider, failure.code, until)
            log.warn(
                "LLM provider is on hold: provider={}, model={}, code={}, until={}",
                model.provider.code,
                model.qualifiedCode,
                failure.code.code,
                applied.until
            )
        }
    }

    private fun notPermitted(reason: String): LlmProviderException {
        return LlmProviderException(
            LlmFailure(
                code = LlmFailureCode.LLM_NOT_PERMITTED,
                provider = model.provider,
                message = "${LlmFailureCode.LLM_NOT_PERMITTED.defaultMessage}: $reason"
            )
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(GuardedLlmModel::class.java)
        const val NOT_PERMITTED = "not-permitted"
    }
}
