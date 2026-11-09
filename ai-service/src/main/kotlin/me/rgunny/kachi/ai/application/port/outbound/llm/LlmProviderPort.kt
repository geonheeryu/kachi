package me.rgunny.kachi.ai.application.port.outbound.llm

import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmKeywordExpansionResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.keyword.AiKeyword

/**
 * LLM 호출을 추상화하는 출력 포트.
 *
 * API 규격별 adapter가 이 포트를 전략 패턴으로 구현한다.
 * 호출하는 쪽은 어느 규격의 adapter가 답하는지 모르고 이 계약만 쓰므로, 규격이 늘어도 application 계층은 바뀌지 않는다.
 */
interface LlmProviderPort {

    fun prepareStorySummary(): PreparedLlmStorySummary

    suspend fun expandKeyword(
        keyword: AiKeyword,
        maxExpansions: Int
    ): LlmKeywordExpansionResult

    suspend fun summarizeStory(
        keywords: List<AiKeyword>,
        previousSummary: PreviousStorySummary?,
        articles: List<StorySummaryArticle>
    ): LlmStorySummaryResult
}
