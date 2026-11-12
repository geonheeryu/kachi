package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.adapter.outbound.llm.LlmHoldSettings
import me.rgunny.kachi.ai.domain.llm.LlmBilling
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.LlmUse
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.io.ClassPathResource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 항목 하나 안의 검증, 항목 사이의 참조 검증, `application.yaml` 바인딩을 보는 테스트.
 */
@DisplayName("LlmProperties")
class LlmPropertiesTest {

    @Test
    @DisplayName("후보에서 참조된 모델의 집합과 용도별 후보 순서를 돌려준다")
    fun exposeCandidates() {
        val properties = AiTestFixture.llmProperties()

        assertEquals(setOf(LlmModel.GROQ_QWEN3_27B, LlmModel.MISTRAL_SMALL_2603), properties.candidateModels)
        assertEquals(listOf(LlmModel.MISTRAL_SMALL_2603), properties.candidates(LlmUse.KEYWORD_EXPANSION))
        assertEquals(Duration.ofSeconds(15), properties.modelOf(LlmModel.MISTRAL_SMALL_2603).slowAfter)
    }

    @Test
    @DisplayName("후보가 없는 용도가 있으면 실패한다")
    fun failWhenUseHasNoCandidates() {
        val error = assertFailsWith<IllegalArgumentException> {
            AiTestFixture.llmProperties(
                uses = mapOf(
                    LlmUse.STORY_SUMMARY to LlmProperties.UseProperties(listOf(LlmModel.GROQ_QWEN3_27B))
                )
            )
        }

        assertEquals("LLM use KEYWORD_EXPANSION의 candidates가 없습니다", error.message)
    }

    @Test
    @DisplayName("후보 모델의 시간 설정이 없으면 실패한다")
    fun failWhenCandidateModelHasNoSettings() {
        val error = assertFailsWith<IllegalArgumentException> {
            AiTestFixture.llmProperties(models = mapOf(LlmModel.GROQ_QWEN3_27B to AiTestFixture.modelProperties()))
        }

        assertEquals("LLM model MISTRAL_SMALL_2603의 설정이 없습니다", error.message)
    }

    @Test
    @DisplayName("후보 모델의 제공자 설정이 없으면 실패한다")
    fun failWhenCandidateProviderIsMissing() {
        val error = assertFailsWith<IllegalArgumentException> {
            AiTestFixture.llmProperties(providers = mapOf(LlmProvider.GROQ to AiTestFixture.providerProperties()))
        }

        assertEquals("LLM provider MISTRAL의 설정이 없습니다", error.message)
    }

    @ParameterizedTest
    @EnumSource(value = LlmBilling::class, mode = EnumSource.Mode.EXCLUDE, names = ["SELF_HOSTED"])
    @DisplayName("후보 모델의 제공자가 self-hosted가 아닌데 api-key가 없으면 실패한다")
    fun failWhenCandidateProviderLacksApiKey(billing: LlmBilling) {
        val error = assertFailsWith<IllegalArgumentException> {
            AiTestFixture.llmProperties(
                providers = mapOf(
                    LlmProvider.GROQ to AiTestFixture.providerProperties(billing = billing, apiKey = " "),
                    LlmProvider.MISTRAL to AiTestFixture.providerProperties()
                )
            )
        }

        assertEquals("LLM provider GROQ의 api-key가 없습니다", error.message)
    }

    @Test
    @DisplayName("후보가 아닌 제공자는 api-key가 없어도 된다")
    fun ignoreApiKeyOfUnreferencedProvider() {
        val properties = AiTestFixture.llmProperties(
            providers = mapOf(
                LlmProvider.GROQ to AiTestFixture.providerProperties(),
                LlmProvider.MISTRAL to AiTestFixture.providerProperties(),
                LlmProvider.OPENROUTER to AiTestFixture.providerProperties(apiKey = "")
            )
        )

        assertEquals(setOf(LlmModel.GROQ_QWEN3_27B, LlmModel.MISTRAL_SMALL_2603), properties.candidateModels)
    }

    @Test
    @DisplayName("self-hosted 후보 제공자는 api-key 없이 통과한다")
    fun allowSelfHostedCandidateWithoutApiKey() {
        val properties = AiTestFixture.llmProperties(
            uses = mapOf(
                LlmUse.STORY_SUMMARY to LlmProperties.UseProperties(listOf(LlmModel.OLLAMA_QWEN3_27B)),
                LlmUse.KEYWORD_EXPANSION to LlmProperties.UseProperties(listOf(LlmModel.OLLAMA_QWEN3_27B))
            )
        )

        assertEquals("", properties.providerOf(LlmModel.OLLAMA_QWEN3_27B).apiKey)
    }

    @Test
    @DisplayName("제공자 base-url은 비어 있을 수 없고 connect-timeout은 양수여야 한다")
    fun rejectBlankBaseUrlAndNonPositiveConnectTimeout() {
        assertFailsWith<IllegalArgumentException> { AiTestFixture.providerProperties(baseUrl = " ") }
        assertFailsWith<IllegalArgumentException> { AiTestFixture.providerProperties(connectTimeout = Duration.ZERO) }
    }

    @Test
    @DisplayName("모델의 slow-after는 양수이고 timeout보다 짧아야 한다")
    fun requireSlowAfterShorterThanTimeout() {
        assertFailsWith<IllegalArgumentException> { AiTestFixture.modelProperties(slowAfter = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> {
            AiTestFixture.modelProperties(timeout = Duration.ofSeconds(10), slowAfter = Duration.ofSeconds(10))
        }
        assertFailsWith<IllegalArgumentException> {
            AiTestFixture.modelProperties(timeout = Duration.ofSeconds(10), slowAfter = Duration.ofSeconds(11))
        }
    }

    @Test
    @DisplayName("hold의 reprobe-after는 양수여야 한다")
    fun rejectNonPositiveReprobeAfter() {
        assertFailsWith<IllegalArgumentException> { LlmHoldSettings(reprobeAfter = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { LlmHoldSettings(reprobeAfter = Duration.ofMinutes(-1)) }
    }

    @Test
    @DisplayName("용도의 후보는 하나 이상이고 같은 모델이 두 번 있을 수 없다")
    fun requireDistinctNonEmptyCandidates() {
        assertFailsWith<IllegalArgumentException> { LlmProperties.UseProperties(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            LlmProperties.UseProperties(listOf(LlmModel.GROQ_QWEN3_27B, LlmModel.GROQ_QWEN3_27B))
        }
    }

    /**
     * `application.yaml`의 값이 enum 키와 단축형 시간 표기 그대로 바인딩되는지 본다.
     */
    @Test
    @DisplayName("application.yaml의 kachi.ai.llm 값이 그대로 바인딩된다")
    fun bindProductionYaml() {
        val properties = productionYamlBinder().bind(LlmProperties.PREFIX, LlmProperties::class.java).get()

        val groq = properties.providers.getValue(LlmProvider.GROQ)
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals(LlmBilling.FREE_TIER, groq.billing)
        assertEquals(Duration.ofSeconds(2), groq.connectTimeout)
        assertEquals(LlmBilling.SELF_HOSTED, properties.providers.getValue(LlmProvider.OLLAMA).billing)

        val ollama = properties.models.getValue(LlmModel.OLLAMA_QWEN3_27B)
        assertEquals(Duration.ofSeconds(150), ollama.timeout)
        assertEquals(Duration.ofSeconds(120), ollama.slowAfter)

        assertEquals(
            listOf(LlmModel.GROQ_QWEN3_27B, LlmModel.MISTRAL_SMALL_2603),
            properties.uses.getValue(LlmUse.STORY_SUMMARY).candidates
        )

        val circuitBreaker = properties.guard.circuitBreaker
        assertEquals(6, circuitBreaker.slidingWindowSize)
        assertEquals(3, circuitBreaker.minimumNumberOfCalls)
        assertEquals(50f, circuitBreaker.failureRateThreshold)
        assertEquals(80f, circuitBreaker.slowCallRateThreshold)
        assertEquals(Duration.ofSeconds(60), circuitBreaker.waitDurationInOpenState)
        assertEquals(2, circuitBreaker.permittedNumberOfCallsInHalfOpenState)
        assertEquals(Duration.ofSeconds(60), properties.guard.cooldown.default)
        assertEquals(Duration.ofMinutes(10), properties.guard.cooldown.max)
        assertEquals(Duration.ofHours(1), properties.guard.hold.reprobeAfter)
    }

    private fun productionYamlBinder(): Binder {
        val factory = YamlPropertiesFactoryBean()
        factory.setResources(ClassPathResource("application.yaml"))
        val yaml = requireNotNull(factory.getObject()) { "application.yaml을 읽지 못했습니다" }
        val source = MapConfigurationPropertySource(
            // ${ENV:} 자리표시 치환값(Binder는 자리표시를 풀지 않음)
            yaml.entries.associate { (key, value) -> key.toString() to value.toString().replace(Regex("\\$\\{[^}]*}"), "placeholder") }
        )

        return Binder(source)
    }
}
