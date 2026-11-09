package me.rgunny.kachi.ai.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import me.rgunny.kachi.ai.adapter.outbound.llm.GuardedLlmModel
import me.rgunny.kachi.ai.adapter.outbound.llm.GuardedLlmModelAdmin
import me.rgunny.kachi.ai.adapter.outbound.llm.ProviderHoldRegistry
import me.rgunny.kachi.ai.adapter.outbound.llm.RoutingLlmProvider
import me.rgunny.kachi.ai.adapter.outbound.llm.openai.OpenAiChatAdapter
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderAdminPort
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.domain.llm.LlmApi
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.LlmUse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * LLM 호출 층을 조립하는 설정.
 *
 * - 전략 패턴: 후보 모델마다 그 제공자의 API 규격에 맞는 adapter를 만든다. adapter는 포트를 규격마다 하나씩
 *   구현하므로 위 층은 규격을 모른 채 다형성으로 부른다. 규격이 늘면 [adapter]의 분기와 adapter 클래스가 하나씩 는다.
 * - 데코레이터 패턴: adapter를 같은 포트로 감싸는 [GuardedLlmModel]을 씌운다. 차단 정책이 바뀌어도 adapter는 그대로다.
 * - 컴포지트 패턴: 같은 포트의 후보 여럿을 묶는 [RoutingLlmProvider]를 호출 포트로 등록한다. 후보를 어떻게 쓰는지는
 *   용도마다 설정 순서대로 시도하는 순차 failover 정책이다. application 서비스는 후보가 몇 개인지, 어느 순서인지 모른다.
 *
 * 호출 경로와 운영 조회가 같은 차단 상태를 봐야 하므로 가드 목록을 빈으로 두고 양쪽이 주입받는다.
 */
@Configuration
class LlmConfig {

    /**
     * 제공자 보류는 계정의 상태라 같은 제공자의 가드들이 한 곳을 봐야 한다.
     */
    @Bean
    fun providerHoldRegistry(): ProviderHoldRegistry = ProviderHoldRegistry()

    /**
     * 후보 모델마다 자기 회로를 가진 가드를 씌운다.
     *
     * 회로를 모델 단위로 두어야 한 모델의 장애가 나머지 모델의 호출을 막지 않고, slow call duration threshold도 그 모델의 값이 된다.
     * WebClient는 제공자마다 하나를 만들고 모델마다 응답 timeout을 덧붙여 복제한다. 층 구분은 [LlmWebClients]에 있다.
     */
    @Bean
    fun guardedLlmModels(
        properties: LlmProperties,
        circuitBreakerRegistry: CircuitBreakerRegistry,
        providerHoldRegistry: ProviderHoldRegistry,
        jsonMapper: JsonMapper,
        clock: Clock
    ): List<GuardedLlmModel> {
        val providerWebClients = properties.candidateModels
            .map { it.provider }
            .distinct()
            .associateWith { provider -> providerWebClient(provider, properties) }

        // Decorator Pattern
        // adapter(전략)를 같은 포트 LlmProviderPort로 감싼다. GuardedLlmModel은 호출을 받으면 서킷·cooldown·hold를 검사하고
        // 통과하면 delegate에 그대로 넘긴다. adapter는 감싸인 사실을 모르고, 라우터는 감싼 것을 adapter와 같은 포트로 본다.
        // 그래서 차단 장치를 더하거나 빼도 adapter와 라우터는 바뀌지 않는다.
        return properties.candidateModels.map { model ->
            GuardedLlmModel(
                delegate = adapter(
                    model = model,
                    webClient = LlmWebClients.forModel(
                        providerWebClient = providerWebClients.getValue(model.provider),
                        responseTimeout = properties.modelOf(model).timeout
                    ),
                    jsonMapper = jsonMapper
                ),
                model = model,
                billing = properties.providerOf(model).billing,
                circuitBreaker = circuitBreakerRegistry.circuitBreaker(model.qualifiedCode, model.qualifiedCode),
                cooldown = properties.guard.cooldown,
                hold = properties.guard.hold,
                providerHolds = providerHoldRegistry,
                clock = clock
            )
        }
    }

    /**
     * 용도마다 설정된 후보 순서대로 가드를 늘어놓아 라우터에 준다.
     */
    @Bean
    fun llmProviderPort(
        properties: LlmProperties,
        guardedLlmModels: List<GuardedLlmModel>,
        clock: Clock
    ): LlmProviderPort {
        val guardByModel = guardedLlmModels.associateBy { it.model }

        // Composite Pattern
        // 같은 포트 LlmProviderPort를 구현하는 가드 여럿을 RoutingLlmProvider 하나로 묶고, 그 하나를 다시 같은 포트로 내보낸다.
        // application 서비스는 포트 하나를 부를 뿐 뒤에 후보가 몇 개인지 모른다.
        // 묶은 후보를 설정 순서대로 시도하는 failover는 RoutingLlmProvider의 정책이지 패턴의 일부가 아니다.
        return RoutingLlmProvider(
            candidates = LlmUse.entries.associateWith { use -> properties.candidates(use).map(guardByModel::getValue) },
            clock = clock
        )
    }

    @Bean
    fun llmProviderAdminPort(
        guardedLlmModels: List<GuardedLlmModel>,
        clock: Clock
    ): LlmProviderAdminPort {
        return GuardedLlmModelAdmin(models = guardedLlmModels, clock = clock)
    }

    /**
     * 제공자의 API 규격에 맞는 adapter 전략을 고른다.
     *
     * 선택 키는 [LlmApi]이고 분기는 여기 한 곳뿐이다. 규격이 늘면 상수가 하나 늘고 이 `when`이 빠진 분기를 컴파일에서 잡는다.
     * 규격이 하나뿐이라 선택을 별도 객체로 두지 않는다.
     */
    private fun adapter(
        model: LlmModel,
        webClient: WebClient,
        jsonMapper: JsonMapper
    ): LlmProviderPort {
        // Strategy Pattern
        // LlmProviderPort가 전략의 공통 계약이고, 규격마다 그것을 구현한 adapter가 구체 전략이다.
        // 어느 전략을 쓸지는 model.provider.api 하나로 여기서 고른다. 위 층은 반환 타입 LlmProviderPort만 보므로
        // 규격이 늘어도 바뀌지 않고, 새 LlmApi 상수에 분기가 빠지면 이 when이 컴파일에서 잡는다.
        return when (model.provider.api) {
            LlmApi.OPENAI_CHAT_COMPLETIONS -> OpenAiChatAdapter(
                webClient = webClient,
                jsonMapper = jsonMapper,
                model = model
            )
        }
    }

    private fun providerWebClient(
        provider: LlmProvider,
        properties: LlmProperties
    ): WebClient {
        val settings = properties.providers.getValue(provider)

        return LlmWebClients.forProvider(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            connectTimeout = settings.connectTimeout
        )
    }
}
