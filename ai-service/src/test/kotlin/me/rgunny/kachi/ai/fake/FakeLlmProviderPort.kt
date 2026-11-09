package me.rgunny.kachi.ai.fake

import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmKeywordExpansionResult
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryPlan
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.keyword.ExpandedKeyword
import me.rgunny.kachi.ai.domain.summary.NewsSummarySentiment
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.fixture.AiTestFixture

/**
 * 키워드 확장과 story 요약을 지원하는 LLM provider fake.
 *
 * [failedKeywords]의 키워드는 확장 호출에서 [IllegalStateException]으로 실패한다.
 * [failureByKeyword]에 예외가 있는 키워드는 그 예외를 그대로 던진다.
 * [storyFailure]가 있으면 story 요약 호출에서 던진다.
 */
class FakeLlmProviderPort : LlmProviderPort {
    var expandCallCount: Int = 0
    var summarizeStoryCallCount: Int = 0
    var failedKeywords: Set<AiKeyword> = emptySet()
    var failureByKeyword: Map<AiKeyword, Throwable> = emptyMap()
    var storyFailure: Throwable? = null
    var storyDevelopmentKind: StoryDevelopmentKind = StoryDevelopmentKind.DEVELOPMENT
    var lastStoryPreviousSummary: PreviousStorySummary? = null
    var lastStoryArticles: List<StorySummaryArticle> = emptyList()

    override fun prepareStorySummary(): PreparedLlmStorySummary {
        return FakePreparedStorySummary(
            plan = LlmStorySummaryPlan(
                provider = AiTestFixture.PROVIDER,
                promptVersion = AiTestFixture.STORY_SUMMARY_PROMPT_VERSION
            ),
            provider = this
        )
    }

    override suspend fun expandKeyword(
        keyword: AiKeyword,
        maxExpansions: Int
    ): LlmKeywordExpansionResult {
        expandCallCount += 1
        failIfRequested(keyword)

        return LlmKeywordExpansionResult(
            expandedKeywords = listOf(
                ExpandedKeyword.of("AI 반도체"),
                ExpandedKeyword.of("GPU")
            ).take(maxExpansions),
            metadata = AiTestFixture.keywordExpansionMetadata()
        )
    }

    override suspend fun summarizeStory(
        keywords: List<AiKeyword>,
        previousSummary: PreviousStorySummary?,
        articles: List<StorySummaryArticle>
    ): LlmStorySummaryResult {
        summarizeStoryCallCount += 1
        lastStoryPreviousSummary = previousSummary
        lastStoryArticles = articles
        storyFailure?.let { throw it }

        return LlmStorySummaryResult(
            title = "${keywords.first().value} story 요약",
            content = "story 요약 본문",
            sentiment = NewsSummarySentiment.NEUTRAL,
            developmentKind = storyDevelopmentKind,
            metadata = AiTestFixture.storySummaryMetadata()
        )
    }

    private fun failIfRequested(keyword: AiKeyword) {
        failureByKeyword[keyword]?.let { throw it }

        if (keyword in failedKeywords) {
            throw IllegalStateException("LLM failure")
        }
    }
}
