package me.rgunny.kachi.ai

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import me.rgunny.kachi.ai.adapter.inbound.outbox.AiOutboxRelayExecutor
import me.rgunny.kachi.ai.adapter.inbound.scheduler.AiOutboxRelayScheduler
import me.rgunny.kachi.ai.adapter.outbound.llm.GuardedLlmModel
import me.rgunny.kachi.ai.adapter.outbound.llm.ProviderHoldRegistry
import me.rgunny.kachi.ai.adapter.outbound.llm.RoutingLlmProvider
import me.rgunny.kachi.ai.application.port.inbound.outbox.FindAiOutboxesUseCase
import me.rgunny.kachi.ai.application.port.inbound.outbox.RecoverAiOutboxUseCase
import me.rgunny.kachi.ai.application.port.inbound.quarantine.FindStoryQuarantinesUseCase
import me.rgunny.kachi.ai.application.port.inbound.quarantine.ReleaseStoryQuarantineUseCase
import me.rgunny.kachi.ai.application.port.inbound.story.FindStorySummariesUseCase
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderAdminPort
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxEventSerializer
import me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxPublisherPort
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySummaryCreatedEvent
import me.rgunny.kachi.ai.application.service.outbox.AiOutboxRelayPolicy
import me.rgunny.kachi.ai.application.service.outbox.RelayAiOutboxService
import me.rgunny.kachi.ai.config.AiEventsProperties
import me.rgunny.kachi.ai.config.AiOutboxRelayProperties
import me.rgunny.kachi.ai.config.LlmProperties
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmUse
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.reactive.TransactionalOperator
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest
@Import(AiServiceTestContainersConfig::class)
class AiServiceApplicationTest {

    @Autowired
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @Autowired
    private lateinit var llmProviderPort: LlmProviderPort

    @Autowired
    private lateinit var llmProviderAdminPort: LlmProviderAdminPort

    @Autowired
    private lateinit var llmProperties: LlmProperties

    @Autowired
    private lateinit var transactionalOperator: TransactionalOperator

    @Autowired
    private lateinit var eventSerializer: AiOutboxEventSerializer

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var relayProperties: AiOutboxRelayProperties

    @Autowired
    private lateinit var eventsProperties: AiEventsProperties

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun contextLoads() {
    }

    @Test
    @DisplayName("도메인 저장과 이벤트 기록을 묶을 트랜잭션 경계가 등록된다")
    fun registerTransactionalOperator() {
        assertNotNull(transactionalOperator)
    }

    /**
     * 발행 계약의 형식은 컨텍스트가 조립한 JsonMapper가 결정한다.
     * 자동 설정이 모듈이나 기본값을 바꾸면 payload 형식이 조용히 달라지므로 여기서 고정한다.
     */
    @Test
    @DisplayName("컨텍스트의 serializer가 발행 계약대로 payload를 만든다")
    fun serializeOutboxEventWithContextSerializer() {
        val summary = AiTestFixture.storySummary()
        val event = StorySummaryCreatedEvent.from(summary)

        val payload = jsonMapper.readValue(eventSerializer.serialize(event), Map::class.java)

        assertEquals(StorySummaryCreatedEvent.SCHEMA_VERSION, payload["schemaVersion"])
        assertEquals(summary.id.value.toString(), payload["summaryId"])
        assertEquals("NEUTRAL", payload["sentiment"])
        assertEquals("2026-06-03T00:00:00Z", payload["createdAt"])
    }

    /**
     * test 프로파일의 기본값은 relay와 events 둘 다 꺼짐이다. 이 테스트는 그 기본값으로 컨텍스트를 띄운다.
     * 켜서 검증하는 것은 각자 properties로 켜는 테스트의 몫이다 — 배선은 [AiOutboxRelayWiringTest], 실 broker 발행은 단계 G의 통합 테스트.
     * 여기서 보는 것은 꺼진 상태에서 빈이 없다는 사실이다. 스위치를 빈 생성 조건에 둔 이유가 그것이라 검증 대상이다.
     */
    @Test
    @DisplayName("relay가 꺼져 있으면 relay 빈이 만들어지지 않는다")
    fun skipRelayBeansWhenRelayIsDisabled() {
        assertFalse(relayProperties.enabled)

        listOf(
            RelayAiOutboxService::class.java,
            AiOutboxRelayExecutor::class.java,
            AiOutboxRelayScheduler::class.java,
            AiOutboxRelayPolicy::class.java
        ).forEach { type ->
            assertTrue(applicationContext.getBeanNamesForType(type).isEmpty(), "${type.simpleName} 빈이 없어야 합니다")
        }
    }

    @Test
    @DisplayName("events가 꺼져 있으면 Kafka 발행 어댑터가 만들어지지 않는다")
    fun skipPublisherBeanWhenEventsAreDisabled() {
        assertFalse(eventsProperties.enabled)

        assertTrue(applicationContext.getBeanNamesForType(AiOutboxPublisherPort::class.java).isEmpty())
    }

    /**
     * 켜지 않아도 운영 yaml의 topic 이름이 바인딩되는지 본다. 소비자가 같은 이름을 구독하므로 값 자체가 계약이다.
     */
    @Test
    @DisplayName("events 설정은 운영 yaml의 topic 이름으로 바인딩된다")
    fun bindProductionEventTopics() {
        assertEquals("ai.summary.created", eventsProperties.topics.summaryCreated)
        assertEquals("ai.story.split-requested", eventsProperties.topics.storySplitRequested)
        assertEquals("ai.story.quarantined", eventsProperties.topics.storyQuarantined)
    }

    /**
     * 운영 API의 진입점은 컨트롤러가 아니라 유스케이스 빈이다.
     * 컨트롤러 슬라이스는 fake로 서기 때문에 실제 빈이 없어도 통과한다.
     */
    @Test
    @DisplayName("운영 API가 쓰는 유스케이스 빈이 모두 등록된다")
    fun registerInternalApiUseCases() {
        listOf(
            FindStoryQuarantinesUseCase::class.java,
            ReleaseStoryQuarantineUseCase::class.java,
            FindStorySummariesUseCase::class.java,
            FindAiOutboxesUseCase::class.java,
            RecoverAiOutboxUseCase::class.java
        ).forEach { type ->
            assertEquals(1, applicationContext.getBeanNamesForType(type).size, "${type.simpleName} 빈이 하나여야 합니다")
        }
    }

    /**
     * test 프로파일은 후보를 Ollama 하나로 덮는다. 참조되지 않은 클라우드 제공자는 클라이언트도 서킷도 없어야 한다.
     */
    @Test
    @DisplayName("test 프로파일의 후보는 모든 용도에서 Ollama 모델 하나다")
    fun bindTestProfileCandidates() {
        LlmUse.entries.forEach { use ->
            assertEquals(listOf(LlmModel.OLLAMA_QWEN3_27B), llmProperties.candidates(use), use.name)
        }
        assertEquals(setOf(LlmModel.OLLAMA_QWEN3_27B), llmProperties.candidateModels)
        assertEquals("http://llm-test", llmProperties.providerOf(LlmModel.OLLAMA_QWEN3_27B).baseUrl)
    }

    @Test
    @DisplayName("모델 차단 상태 조회 포트는 router와 같은 후보 모델을 본다")
    fun shareGuardedModelsWithAdminPort() {
        val router = assertIs<RoutingLlmProvider>(llmProviderPort)

        assertEquals(
            router.candidates.values.flatten().map { assertIs<GuardedLlmModel>(it).model }.toSet(),
            llmProviderAdminPort.statuses().map { it.model }.toSet()
        )
    }

    @Test
    @DisplayName("제공자 보류 registry는 컨텍스트에 하나다")
    fun registerSingleProviderHoldRegistry() {
        assertEquals(1, applicationContext.getBeansOfType(ProviderHoldRegistry::class.java).size)
    }

    @Test
    @DisplayName("후보 모델마다 그 모델의 이름으로 서킷 브레이커 인스턴스가 등록된다")
    fun registerCircuitBreakerPerCandidateModel() {
        val names = circuitBreakerRegistry.allCircuitBreakers.map { it.name }.toSet()

        assertEquals(setOf(LlmModel.OLLAMA_QWEN3_27B.qualifiedCode), names)
    }

    @Test
    @DisplayName("컨텍스트의 서킷 브레이커 설정은 운영 yaml의 비율과 그 모델의 slow-after를 합친 값이다")
    fun bindProductionCircuitBreakerConfig() {
        val config = circuitBreakerRegistry.circuitBreaker(LlmModel.OLLAMA_QWEN3_27B.qualifiedCode).circuitBreakerConfig

        assertEquals(6, config.slidingWindowSize)
        assertEquals(3, config.minimumNumberOfCalls)
        assertEquals(50f, config.failureRateThreshold)
        assertEquals(Duration.ofSeconds(120), config.slowCallDurationThreshold)
        assertEquals(80f, config.slowCallRateThreshold)
        assertEquals(2, config.permittedNumberOfCallsInHalfOpenState)
    }

    @Test
    @DisplayName("LLM 호출 포트는 회로로 감싼 모델을 용도별로 순회하는 router다")
    fun llmProviderPortRoutesGuardedModels() {
        val router = assertIs<RoutingLlmProvider>(llmProviderPort)

        assertEquals(LlmUse.entries.toSet(), router.candidates.keys)
        router.candidates.values.forEach { candidates ->
            assertTrue(candidates.isNotEmpty())
            candidates.forEach { assertIs<GuardedLlmModel>(it) }
        }
    }
}
