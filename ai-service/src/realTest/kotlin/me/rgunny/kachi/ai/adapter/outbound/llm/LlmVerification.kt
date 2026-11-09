package me.rgunny.kachi.ai.adapter.outbound.llm

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmGenerationMetadata
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.llm.LlmBilling
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.LlmUse
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.LlmProfileCandidates
import me.rgunny.kachi.ai.support.OllamaTags
import me.rgunny.kachi.ai.support.ProviderModelList
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.web.reactive.function.client.bodyToMono
import java.net.URI
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * 프로파일의 후보 모델을 실제 제공자에 불러 확인하는 테스트 본문.
 *
 * 모델마다 제공자의 모델 목록에 code가 있는지(토큰 0), 후보로 오른 용도마다 생성 1회가 계약대로 돌아오는지 본다.
 * Ollama 모델은 digest도 남긴다(tag는 이동 alias).
 * 서버나 secret이 없으면 skip하지 않고 실패한다.
 * 프로파일은 시스템 프로퍼티 `kachi.llm.profile`이고 없으면 local이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LlmVerification(
    private val includes: (LlmBilling) -> Boolean
) {

    private val candidates = LlmProfileCandidates(profile = System.getProperty(PROFILE_PROPERTY, DEFAULT_PROFILE))

    fun models(): List<LlmModel> {
        return candidates.properties.candidateModels.filter { includes(billingOf(it)) }
    }

    fun useCandidates(): List<Arguments> {
        return candidates.useCandidates
            .filter { (_, model) -> includes(billingOf(model)) }
            .map { (use, model) -> Arguments.of(use, model) }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("models")
    @DisplayName("제공자의 모델 목록에 후보 모델의 code가 있다")
    fun modelIsListed(model: LlmModel) {
        val listed = candidates.providerWebClient(model.provider)
            .get()
            .uri(MODELS_PATH)
            .retrieve()
            .bodyToMono<ProviderModelList>()
            .block()
            ?.data
            .orEmpty()
            .map { it.id }

        assertTrue(model.code in listed, "${model.provider.code}의 모델 목록에 ${model.code}가 없습니다: $listed")

        report("listed", model, "billing=${billingOf(model)} baseUrl=${candidates.properties.providerOf(model).baseUrl}")
        if (model.provider == LlmProvider.OLLAMA) {
            report("digest", model, "digest=${ollamaDigest(model)}")
        }
    }

    @ParameterizedTest(name = "{0} {1}", allowZeroInvocations = true)
    @MethodSource("useCandidates")
    @DisplayName("후보로 오른 용도마다 실제 생성 1회가 계약대로 돌아온다")
    fun generates(
        use: LlmUse,
        model: LlmModel
    ) {
        val guarded = candidates.model(model)

        val (generated, latency) = measureTimedValue {
            runBlocking {
                when (use) {
                    LlmUse.STORY_SUMMARY -> summarizeStory(guarded)
                    LlmUse.KEYWORD_EXPANSION -> expand(guarded)
                }
            }
        }

        val metadata = generated.metadata
        assertTrue(metadata.tokenUsage.inputTokens > 0, "input token이 보고되지 않았습니다")
        assertTrue(metadata.tokenUsage.outputTokens > 0, "output token이 보고되지 않았습니다")

        report(
            "generated",
            model,
            "use=$use served=${metadata.model} latency=${latency.inWholeMilliseconds}ms " +
                "tokens=${metadata.tokenUsage.inputTokens}/${metadata.tokenUsage.outputTokens} ${generated.outcome}"
        )
    }

    private suspend fun summarizeStory(guarded: GuardedLlmModel): Generated {
        val result = guarded.summarizeStory(
            keywords = listOf(AiTestFixture.keyword()),
            previousSummary = PREVIOUS_STORY_SUMMARY,
            articles = STORY_ARTICLES
        )

        return Generated(
            metadata = result.metadata,
            outcome = "developmentKind=${result.developmentKind} sentiment=${result.sentiment} title=\"${result.title}\""
        )
    }

    private suspend fun expand(guarded: GuardedLlmModel): Generated {
        val result = guarded.expandKeyword(keyword = AiTestFixture.keyword(), maxExpansions = MAX_EXPANSIONS)

        return Generated(
            metadata = result.metadata,
            outcome = "keywords=${result.expandedKeywords.map { it.value }}"
        )
    }

    /**
     * Ollama 고유 tags API로 모델의 digest를 묻는다(OpenAI 규격 목록에는 digest 없음).
     *
     * 주소는 base-url에서 규격 경로(`/v1`)를 뗀 것이다.
     */
    private fun ollamaDigest(model: LlmModel): String {
        val baseUrl = candidates.properties.providerOf(model).baseUrl.removeSuffix("/").removeSuffix(OPENAI_PATH_PREFIX)
        val tags = candidates.providerWebClient(model.provider)
            .get()
            .uri(URI.create("$baseUrl$OLLAMA_TAGS_PATH"))
            .retrieve()
            .bodyToMono<OllamaTags>()
            .block()
            ?.models
            .orEmpty()

        return tags.firstOrNull { it.name == model.code }?.digest
            ?: error("Ollama tags에 ${model.code}가 없습니다: ${tags.map { it.name }}")
    }

    private fun billingOf(model: LlmModel): LlmBilling = candidates.properties.providerOf(model).billing

    private fun report(
        event: String,
        model: LlmModel,
        detail: String
    ) {
        println("[realTest] profile=${candidates.profile} $event model=${model.name} code=${model.qualifiedCode} $detail")
    }

    /**
     * 생성 1회의 결과.
     *
     * [outcome]은 보고 줄에 붙는 결과 요약 문자열이다.
     */
    private data class Generated(
        val metadata: LlmGenerationMetadata,
        val outcome: String
    )

    private companion object {
        const val PROFILE_PROPERTY = "kachi.llm.profile"
        const val DEFAULT_PROFILE = "local"
        const val MODELS_PATH = "/models"
        const val OPENAI_PATH_PREFIX = "/v1"
        const val OLLAMA_TAGS_PATH = "/api/tags"
        const val MAX_EXPANSIONS = 3

        /** 직전 요약이 있는 사건에 후속 기사 하나가 붙은 story 요약 입력의 직전 요약. */
        val PREVIOUS_STORY_SUMMARY = PreviousStorySummary(
            title = "NVIDIA, 데이터센터 매출 신기록",
            content = "NVIDIA가 AI 수요 확대로 데이터센터 부문에서 기록적인 매출을 보고했다."
        )
        val STORY_ARTICLES = listOf(
            StorySummaryArticle(
                source = "GOOGLE",
                title = "NVIDIA raises full-year guidance after record quarter",
                excerpt = "Following record data center revenue, NVIDIA raised its full-year revenue guidance above analyst expectations.",
                publishedAt = AiTestFixture.NOW
            )
        )
    }
}
