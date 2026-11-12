package me.rgunny.kachi.ai.fake

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmGenerationMetadata
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmKeywordExpansionResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryPlan
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.keyword.ExpandedKeyword
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.PromptVersion
import me.rgunny.kachi.ai.domain.llm.TokenUsage
import me.rgunny.kachi.ai.domain.summary.NewsSummarySentiment
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.fixture.AiTestFixture
import java.time.Duration

/**
 * 어느 후보가 호출됐는지 이름으로 식별할 수 있는 LLM 호출 fake.
 *
 * 응답 metadata의 requestedModel과 model은 [name]이다.
 * [failures]에 넣은 예외를 호출 순서대로 하나씩 던진다.
 * [failures]가 비면 성공한다.
 * 호출마다 [callLog]에 [name]을 남기고, [gate]가 있으면 완료까지 기다린 뒤 [callDelay]만큼 지연한다.
 */
open class NamedLlmProviderPort(
    val name: String,
    private val provider: LlmProvider = AiTestFixture.PROVIDER
) : LlmProviderPort {
    var expandCallCount: Int = 0
    var summarizeStoryCallCount: Int = 0
    val failures: ArrayDeque<Throwable> = ArrayDeque()
    var callDelay: Duration = Duration.ZERO
    var gate: CompletableDeferred<Unit>? = null
    var callLog: MutableList<String> = mutableListOf()

    val callCount: Int
        get() = expandCallCount + summarizeStoryCallCount

    override suspend fun expandKeyword(
        keyword: AiKeyword,
        maxExpansions: Int
    ): LlmKeywordExpansionResult {
        expandCallCount += 1
        awaitCall()

        return LlmKeywordExpansionResult(
            expandedKeywords = listOf(ExpandedKeyword.of("AI 반도체")),
            metadata = metadata(AiTestFixture.KEYWORD_EXPANSION_PROMPT_VERSION)
        )
    }

    override fun prepareStorySummary(): PreparedLlmStorySummary {
        return FakePreparedStorySummary(
            plan = LlmStorySummaryPlan(
                provider = provider,
                promptVersion = AiTestFixture.STORY_SUMMARY_PROMPT_VERSION
            ),
            provider = this
        )
    }

    override suspend fun summarizeStory(
        keywords: List<AiKeyword>,
        previousSummary: PreviousStorySummary?,
        articles: List<StorySummaryArticle>
    ): LlmStorySummaryResult {
        summarizeStoryCallCount += 1
        awaitCall()

        return LlmStorySummaryResult(
            title = "story 요약",
            content = "story 본문",
            sentiment = NewsSummarySentiment.UNKNOWN,
            developmentKind = StoryDevelopmentKind.DEVELOPMENT,
            metadata = metadata(AiTestFixture.STORY_SUMMARY_PROMPT_VERSION)
        )
    }

    private suspend fun awaitCall() {
        callLog += name
        gate?.await()

        if (!callDelay.isZero) {
            delay(callDelay.toMillis())
        }

        failures.removeFirstOrNull()?.let { throw it }
    }

    private fun metadata(promptVersion: PromptVersion): LlmGenerationMetadata {
        return LlmGenerationMetadata(
            provider = provider,
            requestedModel = name,
            model = name,
            promptVersion = promptVersion,
            tokenUsage = TokenUsage(inputTokens = 1, outputTokens = 1)
        )
    }
}
