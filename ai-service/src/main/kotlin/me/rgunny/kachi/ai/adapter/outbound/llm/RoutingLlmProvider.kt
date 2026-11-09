package me.rgunny.kachi.ai.adapter.outbound.llm

import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmKeywordExpansionResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmFailure
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmUse
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * 용도마다 정해진 후보 순서대로 모델을 호출하는 LLM 라우터.
 *
 * 후보 하나가 실패하면 실패의 종류를 가리지 않고 다음 후보로 넘긴다.
 * 한 번의 호출에서 후보 하나는 최대 한 번만 시도한다.
 * 순서는 설정 그대로다.
 * 후보 하나의 호출 가능 여부는 [LlmProviderCandidate]가 판단한다.
 */
class RoutingLlmProvider(
    val candidates: Map<LlmUse, List<LlmProviderCandidate>>,
    private val clock: Clock
) : LlmProviderPort {

    init {
        LlmUse.entries.forEach { use ->
            require(!candidates[use].isNullOrEmpty()) { "LLM use ${use.name}의 후보가 없습니다" }
        }
    }

    /**
     * 첫 후보의 plan을 든 실행 단위를 돌려준다.
     *
     * 후보가 전부 차단된 상태여도 실패하지 않는다.
     */
    override fun prepareStorySummary(): PreparedLlmStorySummary {
        val order = candidatesOf(LlmUse.STORY_SUMMARY)

        return RoutingPreparedStorySummary(
            plan = order.first().prepareStorySummary().plan,
            router = this,
            order = order
        )
    }

    override suspend fun expandKeyword(
        keyword: AiKeyword,
        maxExpansions: Int
    ): LlmKeywordExpansionResult {
        return callWithFailover(candidatesOf(LlmUse.KEYWORD_EXPANSION)) { it.expandKeyword(keyword, maxExpansions) }
    }

    override suspend fun summarizeStory(
        keywords: List<AiKeyword>,
        previousSummary: PreviousStorySummary?,
        articles: List<StorySummaryArticle>
    ): LlmStorySummaryResult {
        return callWithFailover(candidatesOf(LlmUse.STORY_SUMMARY)) {
            it.summarizeStory(keywords, previousSummary, articles)
        }
    }

    /**
     * 후보를 순서대로 한 번씩만 호출하고, 처음 성공한 결과를 돌려준다.
     *
     * 후보를 모두 쓰고도 성공하지 못하면 [LlmProviderException]을 던진다.
     * 실제 호출의 실패가 있으면 마지막 실패를 대표로, 실제 호출 전부를 attempts에 싣는다.
     * 실제 호출이 한 건도 없으면 [LlmFailureCode.LLM_NOT_PERMITTED]다.
     */
    suspend fun <T> callWithFailover(
        order: List<LlmProviderCandidate>,
        call: suspend (LlmProviderCandidate) -> T
    ): T {
        val failures = mutableListOf<LlmFailure>()
        var lastCallFailure: LlmProviderException? = null

        for ((index, candidate) in order.withIndex()) {
            try {
                return call(candidate)
            } catch (exception: LlmProviderException) {
                val failure = exception.failure

                // 1. 실패를 모으고, 실제 호출의 실패만 대표 후보로 남긴다.
                failures += failure
                if (failure.fromActualCall) {
                    lastCallFailure = exception
                }

                // 2. 실패의 종류를 가리지 않고 다음 후보로 넘긴다.
                log.warn(
                    "LLM model call failed. Trying the next candidate: model={}, code={}, next={}",
                    candidate.model.qualifiedCode,
                    failure.code.code,
                    order.getOrNull(index + 1)?.model?.qualifiedCode ?: LlmFailure.NO_PROVIDER
                )
            }
        }

        // 3. 실제 호출이 있었으면 마지막 실패를 대표로 올린다.
        lastCallFailure?.let { last ->
            log.warn(
                "All LLM candidates were tried without success: attempts=[{}]",
                failures.joinToString { "${it.providerCode}=${it.code.code}" }
            )
            throw LlmProviderException(
                failure = last.failure,
                cause = last,
                attempts = failures.filter { it.fromActualCall }
            )
        }

        throw noCandidateAvailable(order)
    }

    /**
     * 실제 호출이 한 건도 나가지 않은 실패.
     *
     * provider는 null이고, 메시지에 후보마다 빠진 사유를 열거한다.
     */
    private fun noCandidateAvailable(order: List<LlmProviderCandidate>): LlmProviderException {
        val now = Instant.now(clock)
        val reasons = order.joinToString { "${it.model.qualifiedCode}=${it.blockedReason(now)}" }
        val message = "no llm candidate available: $reasons"

        log.error(message)

        return LlmProviderException(
            LlmFailure(
                code = LlmFailureCode.LLM_NOT_PERMITTED,
                provider = null,
                message = message
            )
        )
    }

    private fun candidatesOf(use: LlmUse): List<LlmProviderCandidate> = candidates.getValue(use)

    private companion object {
        val log = LoggerFactory.getLogger(RoutingLlmProvider::class.java)
    }
}
