package me.rgunny.kachi.ai.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * LLM 모델 호출에 적용할 서킷 브레이커와 registry 설정.
 *
 * failure rate·slow call rate·wait duration은 모델 전체가 공유하고, slow call duration threshold와 sliding window는 모델마다 갈린다.
 * registry에는 후보 모델마다 그 모델의 이름으로 설정을 등록해 두고, 조립이 같은 이름으로 인스턴스를 꺼낸다.
 * registry를 쓰는 이유는 이름으로 인스턴스를 꺼내 쓸 수 있고, metric 수집을 붙일 때의 진입점이기 때문이다.
 */
@Configuration
class LlmCircuitBreakerConfig {

    @Bean
    fun llmCircuitBreakerRegistry(properties: LlmProperties): CircuitBreakerRegistry {
        val configs = properties.candidateModels.associate { model ->
            model.qualifiedCode to circuitBreakerConfig(properties.guard.circuitBreaker, properties.modelOf(model).slowAfter)
        }

        return CircuitBreakerRegistry.of(configs)
    }

    /**
     * 실제 호출에서 나온 일시 실패만 회로를 여는 근거로 삼는다.
     *
     * 응답 계약 위반은 모델이 살아 있다는 증거이고, 404나 402는 한 건으로 확정이라 비율이 아니라 hold로 다룬다.
     * 집계 기준은 시간이 아니라 호출 횟수다. 호출량이 적어 시간 window는 비율이 흔들린다.
     */
    fun circuitBreakerConfig(
        properties: LlmCircuitBreakerProperties,
        slowCallDurationThreshold: Duration
    ): CircuitBreakerConfig {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize)
            .minimumNumberOfCalls(properties.minimumNumberOfCalls)
            .failureRateThreshold(properties.failureRateThreshold)
            .slowCallDurationThreshold(slowCallDurationThreshold)
            .slowCallRateThreshold(properties.slowCallRateThreshold)
            .waitDurationInOpenState(properties.waitDurationInOpenState)
            .permittedNumberOfCallsInHalfOpenState(properties.permittedNumberOfCallsInHalfOpenState)
            // 차단이 만든 실패 제외(가드가 집계 전에 던지지만 호출 순서에 기대지 않음)
            .recordException { it is LlmProviderException && it.failure.recordsInCircuit }
            .build()
    }
}
