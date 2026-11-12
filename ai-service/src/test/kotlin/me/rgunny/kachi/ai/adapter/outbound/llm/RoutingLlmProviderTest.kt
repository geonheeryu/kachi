package me.rgunny.kachi.ai.adapter.outbound.llm

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmUse
import me.rgunny.kachi.ai.fake.FakeLlmProviderCandidate
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.MutableClock
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 순회 정책만 검증하는 테스트.
 */
@DisplayName("RoutingLlmProvider")
class RoutingLlmProviderTest {
    private val clock = MutableClock()
    private val callLog = mutableListOf<String>()
    private val first = candidate(LlmModel.GROQ_QWEN3_27B)
    private val second = candidate(LlmModel.MISTRAL_SMALL_2603)
    private val third = candidate(LlmModel.OLLAMA_QWEN3_27B)
    private val router = router(
        summary = listOf(first, second),
        expansion = listOf(first, second)
    )

    @Test
    @DisplayName("후보를 설정 순서 그대로 시도한다")
    fun tryCandidatesInConfiguredOrder() = runBlocking {
        val router = router(summary = listOf(third, first, second), expansion = listOf(first))
        listOf(third, first).forEach { it.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }

        val result = router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(listOf(third.name, first.name, second.name), callLog)
        assertEquals(second.name, result.metadata.model)
    }

    @Test
    @DisplayName("용도마다 자기 후보 목록을 쓴다")
    fun useCandidatesOfEachUse() = runBlocking {
        val router = router(summary = listOf(first), expansion = listOf(third))

        router.summarizeStory(KEYWORDS, null, ARTICLES)
        router.expandKeyword(KEYWORD, 3)

        assertEquals(listOf(first.name, third.name), callLog)
    }

    @Test
    @DisplayName("후보가 없는 용도가 있으면 생성할 수 없다")
    fun failWhenAnyUseHasNoCandidates() {
        assertFailsWith<IllegalArgumentException> {
            RoutingLlmProvider(candidates = mapOf(LlmUse.STORY_SUMMARY to listOf(first)), clock = clock)
        }
        assertFailsWith<IllegalArgumentException> {
            RoutingLlmProvider(
                candidates = mapOf(
                    LlmUse.STORY_SUMMARY to listOf(first),
                    LlmUse.KEYWORD_EXPANSION to emptyList()
                ),
                clock = clock
            )
        }
    }

    @Test
    @DisplayName("재시도 가능한 실패면 다음 후보를 호출한다")
    fun failoverOnRetryableFailure() = runBlocking {
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        val result = router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(1, first.summarizeStoryCallCount)
        assertEquals(1, second.summarizeStoryCallCount)
        assertEquals(second.name, result.metadata.model)
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureCode::class, names = ["LLM_INVALID_RESPONSE", "LLM_REQUEST_REJECTED"])
    @DisplayName("입력 탓 실패도 다음 후보를 호출한다")
    fun failoverOnInputFailure(code: LlmFailureCode) = runBlocking {
        first.failures += AiTestFixture.llmProviderException(code)

        val result = router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(1, first.summarizeStoryCallCount)
        assertEquals(second.name, result.metadata.model)
    }

    @Test
    @DisplayName("시도한 후보 전부가 입력 탓으로 끝나면 결과 예외가 입력 탓으로 확정한다")
    fun allInputWhenEveryCandidateFailsByInput() = runBlocking {
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_REQUEST_REJECTED)
        second.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertTrue(failure.allInput)
        assertEquals(LlmFailureCode.LLM_INVALID_RESPONSE, failure.failure.code)
        assertEquals(
            listOf(LlmFailureCode.LLM_REQUEST_REJECTED, LlmFailureCode.LLM_INVALID_RESPONSE),
            failure.attempts.map { it.code }
        )
    }

    @Test
    @DisplayName("일부 후보만 입력 탓이면 입력 탓으로 확정하지 않는다")
    fun notAllInputWhenOnlySomeCandidatesFailByInput() = runBlocking {
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)
        second.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_SERVER_ERROR)

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertFalse(failure.allInput)
        assertEquals(2, failure.attempts.size)
    }

    @Test
    @DisplayName("차단된 후보는 시도 기록에 들어가지 않는다")
    fun blockedCandidatesAreNotAttempts() = runBlocking {
        first.blockedBy = OPEN
        second.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(listOf(LlmFailureCode.LLM_INVALID_RESPONSE), failure.attempts.map { it.code })
        assertTrue(failure.allInput)
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureCode::class, names = [
        "LLM_MODEL_NOT_FOUND",
        "LLM_UNAUTHORIZED",
        "LLM_PAYMENT_REQUIRED",
        "LLM_FORBIDDEN"
    ])
    @DisplayName("모델·계정 실패는 다른 후보로 넘어간다")
    fun failoverOnModelAndProviderFailure(code: LlmFailureCode) = runBlocking {
        first.failures += AiTestFixture.llmProviderException(code)

        val result = router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(second.name, result.metadata.model)
    }

    @Test
    @DisplayName("분류되지 않은 LLM 실패는 다른 후보로 넘어간다")
    fun failoverOnUnknownFailure() = runBlocking {
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_UNKNOWN_ERROR)

        val result = router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(second.name, result.metadata.model)
    }

    @Test
    @DisplayName("LLM 실패가 아닌 예외는 즉시 전파한다")
    fun propagateNonLlmException() = runBlocking {
        first.failures += IllegalArgumentException("boom")

        assertFailsWith<IllegalArgumentException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(0, second.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("각 후보는 한 호출에 최대 한 번만 시도한다")
    fun tryEachCandidateOnce() = runBlocking {
        val candidates = listOf(first, second, third)
        val router = router(summary = candidates, expansion = candidates)
        candidates.forEach { it.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT) }

        assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        candidates.forEach { assertEquals(1, it.summarizeStoryCallCount, "${it.name} must be called once") }
        assertEquals(3, callLog.size)
    }

    @Test
    @DisplayName("차단된 후보는 호출 없이 건너뛴다")
    fun skipBlockedCandidateWithoutCall() = runBlocking {
        first.blockedBy = OPEN

        val result = router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(0, first.summarizeStoryCallCount)
        assertEquals(1, second.summarizeStoryCallCount)
        assertEquals(second.name, result.metadata.model)
    }

    @Test
    @DisplayName("모든 후보가 차단되면 호출 없이 실패한다")
    fun failWithoutCallWhenAllCandidatesAreBlocked() = runBlocking {
        listOf(first, second).forEach { it.blockedBy = OPEN }

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, failure.failure.code)
        assertNull(failure.failure.provider)
        assertFalse(failure.allInput)
        assertEquals(0, first.summarizeStoryCallCount + second.summarizeStoryCallCount)
        assertEquals(
            "no llm candidate available: ${first.name}=$OPEN, ${second.name}=$OPEN",
            failure.failure.message
        )
    }

    @Test
    @DisplayName("실제 호출이 있었으면 차단이 마지막이어도 실제 실패를 전파한다")
    fun propagateActualFailureWhenBlockedComesLast() = runBlocking {
        val thrown = AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)
        first.failures += thrown
        second.blockedBy = OPEN

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(thrown.failure, failure.failure)
        assertEquals(thrown, failure.cause)
    }

    @Test
    @DisplayName("차단이 먼저였어도 실제 실패를 전파한다")
    fun propagateActualFailureWhenBlockedComesFirst() = runBlocking {
        first.blockedBy = OPEN
        second.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_TIMEOUT, failure.failure.code)
        assertEquals(1, second.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("실제 호출이 여럿이면 마지막 실제 실패를 전파한다")
    fun propagateLastActualFailure() = runBlocking {
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)
        second.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_RATE_LIMITED)

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_RATE_LIMITED, failure.failure.code)
    }

    @Test
    @DisplayName("차단 사유가 후보마다 다르면 각각 열거한다")
    fun listBlockedReasonPerCandidate() = runBlocking {
        first.blockedBy = OPEN
        second.blockedBy = COOLDOWN

        val failure = assertFailsWith<LlmProviderException> { router.summarizeStory(KEYWORDS, null, ARTICLES) }

        assertEquals(
            "no llm candidate available: ${first.name}=$OPEN, ${second.name}=$COOLDOWN",
            failure.failure.message
        )
    }

    @Test
    @DisplayName("요약 plan은 첫 후보의 것이고 그 후보를 먼저 호출한다")
    fun planComesFromFirstCandidate() = runBlocking {
        val prepared = router.prepareStorySummary()
        prepared.summarize(KEYWORDS, null, ARTICLES)

        assertEquals(first.model.provider, prepared.plan.provider)
        assertEquals(first.name, callLog.first())
    }

    @Test
    @DisplayName("첫 후보가 차단돼 있어도 plan은 첫 후보의 것이다")
    fun planIgnoresAvailability() = runBlocking {
        first.blockedBy = OPEN

        val prepared = router.prepareStorySummary()
        val result = prepared.summarize(KEYWORDS, null, ARTICLES)

        assertEquals(first.model.provider, prepared.plan.provider)
        assertEquals(second.name, result.metadata.model)
    }

    @Test
    @DisplayName("plan의 후보가 실패하면 다음 후보가 요약한다")
    fun summarizeWithNextCandidateWhenPlanCandidateFails() = runBlocking {
        val prepared = router.prepareStorySummary()
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        val result = prepared.summarize(KEYWORDS, null, ARTICLES)

        assertEquals(second.name, result.metadata.model)
        assertEquals(first.model.provider, prepared.plan.provider)
    }

    @Test
    @DisplayName("모든 후보가 차단돼도 요약 준비는 plan을 돌려준다")
    fun prepareReturnsPlanWhenAllCandidatesAreBlocked() = runBlocking {
        listOf(first, second).forEach { it.blockedBy = OPEN }

        val prepared = router.prepareStorySummary()
        val failure = assertFailsWith<LlmProviderException> { prepared.summarize(KEYWORDS, null, ARTICLES) }

        assertEquals(LlmFailureCode.LLM_NOT_PERMITTED, failure.failure.code)
        assertNull(failure.failure.provider)
    }

    @Test
    @DisplayName("차단이 풀린 후보는 다시 후보가 된다")
    fun retryCandidateOnceUnblocked() = runBlocking {
        first.blockedBy = OPEN
        router.summarizeStory(KEYWORDS, null, ARTICLES)
        assertEquals(0, first.summarizeStoryCallCount)

        first.blockedBy = null
        router.summarizeStory(KEYWORDS, null, ARTICLES)

        assertEquals(1, first.summarizeStoryCallCount)
    }

    @Test
    @DisplayName("키워드 확장도 같은 failover를 따른다")
    fun expandKeywordFollowsFailover() = runBlocking {
        first.failures += AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

        val result = router.expandKeyword(KEYWORD, 3)

        assertEquals(1, first.expandCallCount)
        assertEquals(second.name, result.metadata.model)
    }

    private fun candidate(model: LlmModel): FakeLlmProviderCandidate {
        return FakeLlmProviderCandidate(model).also { it.callLog = callLog }
    }

    private fun router(
        summary: List<FakeLlmProviderCandidate>,
        expansion: List<FakeLlmProviderCandidate>
    ): RoutingLlmProvider {
        return RoutingLlmProvider(
            candidates = mapOf(
                LlmUse.STORY_SUMMARY to summary,
                LlmUse.KEYWORD_EXPANSION to expansion
            ),
            clock = clock
        )
    }

    private companion object {
        const val OPEN = "open"
        const val COOLDOWN = "cooldown"
        val KEYWORD: AiKeyword = AiTestFixture.keyword()
        val KEYWORDS: List<AiKeyword> = listOf(KEYWORD)
        val ARTICLES = listOf(AiTestFixture.storySummaryArticle())
    }
}
