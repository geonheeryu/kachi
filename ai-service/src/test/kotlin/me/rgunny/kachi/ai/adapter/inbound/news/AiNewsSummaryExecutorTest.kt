package me.rgunny.kachi.ai.adapter.inbound.news

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.port.inbound.news.model.ExplicitSummaryWindowRequest
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizeNewsCommand
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.fake.BlockingSummarizeNewsUseCase
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.fake.FakeExecutionLockPort
import me.rgunny.kachi.ai.fake.RecordingSummarizeNewsUseCase
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@DisplayName("AiNewsSummaryExecutor")
class AiNewsSummaryExecutorTest {

    @Test
    @DisplayName("lock을 확인할 수 없으면 요약을 실행하지 않는다")
    fun doNotSummarizeWhenLockIsUnavailable() = runBlocking {
        val useCase = RecordingSummarizeNewsUseCase()
        val cause = IllegalStateException("lock 저장소 장애")
        val executor = AiNewsSummaryExecutor(useCase, FakeExecutionLockPort(ExecutionLockOutcome.Unavailable(cause)))
        val command = SummarizeNewsCommand(
            keywords = listOf(AiKeyword.of("NVIDIA")),
            window = ExplicitSummaryWindowRequest(from = null, to = null)
        )

        val result = executor.execute(command)

        val unavailable = assertIs<AiNewsSummaryLockUnavailable>(result)
        assertEquals(cause, unavailable.cause)
        assertEquals(0, useCase.invokeCount)
    }

    @Test
    @DisplayName("실행 중인 뉴스 요약이 있으면 중복 요청을 건너뛴다")
    fun skipWhenNewsSummaryIsAlreadyRunning() = runBlocking {
        val useCase = BlockingSummarizeNewsUseCase()
        val executor = AiNewsSummaryExecutor(useCase, AiTestFixture.executionLock())
        val command = SummarizeNewsCommand(
            keywords = listOf(AiKeyword.of("NVIDIA")),
            window = ExplicitSummaryWindowRequest(from = null, to = null)
        )

        val firstExecution = async {
            executor.execute(command)
        }
        useCase.started.await()

        val secondExecution = executor.execute(command)
        useCase.release.complete(Unit)

        assertIs<AiNewsSummaryAlreadyRunning>(secondExecution)
        assertEquals(AiTestFixture.NOW, secondExecution.runningSummary.startedAt)
        assertIs<AiNewsSummaryStarted>(firstExecution.await())
        assertEquals(1, useCase.executeCount)
    }

}
