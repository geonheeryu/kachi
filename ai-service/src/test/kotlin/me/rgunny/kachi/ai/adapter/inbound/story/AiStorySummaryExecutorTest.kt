package me.rgunny.kachi.ai.adapter.inbound.story

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesCommand
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesResult
import me.rgunny.kachi.ai.application.port.outbound.lock.model.AiExecutionLock
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.fake.BlockingSummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.fake.FakeExecutionLockPort
import me.rgunny.kachi.ai.fake.RecordingSummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * story 요약 tick executor의 lock 결과 변환을 보는 테스트.
 */
@DisplayName("AiStorySummaryExecutor")
class AiStorySummaryExecutorTest {

    private val command = SummarizeDueStoriesCommand(maxStories = 20)

    @Test
    @DisplayName("lock을 얻으면 유스케이스를 한 번 부르고 그 결과를 돌려준다")
    fun summarizeOnceUnderLock() = runBlocking {
        val useCase = RecordingSummarizeDueStoriesUseCase()
        useCase.result = SummarizeDueStoriesResult(due = 3, created = 2, skipped = 1, failed = 0, aborted = false)
        val executor = AiStorySummaryExecutor(useCase, AiTestFixture.executionLock())

        val result = executor.execute(command)

        val started = assertIs<AiStorySummaryStarted>(result)
        assertEquals(useCase.result, started.result)
        assertEquals(listOf(command), useCase.commands)
    }

    @Test
    @DisplayName("lock을 확인할 수 없으면 story 요약 lock의 실패 원인을 돌려주고 요약을 실행하지 않는다")
    fun doNotSummarizeWhenLockIsUnavailable() = runBlocking {
        val useCase = RecordingSummarizeDueStoriesUseCase()
        val cause = IllegalStateException("lock 저장소 장애")
        val lock = FakeExecutionLockPort(ExecutionLockOutcome.Unavailable(cause))
        val executor = AiStorySummaryExecutor(useCase, lock)

        val result = executor.execute(command)

        val unavailable = assertIs<AiStorySummaryLockUnavailable>(result)
        assertEquals(cause, unavailable.cause)
        assertEquals(AiExecutionLock.STORY_SUMMARY, lock.requestedTarget)
        assertEquals(0, useCase.commands.size)
    }

    @Test
    @DisplayName("실행 중인 tick이 있으면 중복 요청을 건너뛰고 앞선 tick의 시작 시각을 돌려준다")
    fun skipWhenTickIsAlreadyRunning() = runBlocking {
        val useCase = BlockingSummarizeDueStoriesUseCase()
        val executor = AiStorySummaryExecutor(useCase, AiTestFixture.executionLock())

        val firstExecution = async { executor.execute(command) }
        useCase.started.await()

        val secondExecution = executor.execute(command)
        useCase.release.complete(Unit)

        val alreadyRunning = assertIs<AiStorySummaryAlreadyRunning>(secondExecution)
        assertEquals(AiTestFixture.NOW, alreadyRunning.runningSummary.startedAt)
        assertIs<AiStorySummaryStarted>(firstExecution.await())
        assertEquals(1, useCase.executeCount)
    }

    @Test
    @DisplayName("유스케이스가 실패하면 예외를 전파하고 lock을 풀어 다음 요청을 실행한다")
    fun releaseLockAfterFailure() = runBlocking {
        val useCase = RecordingSummarizeDueStoriesUseCase()
        val executor = AiStorySummaryExecutor(useCase, AiTestFixture.executionLock())
        useCase.failure = IllegalStateException("mongo down")

        assertFailsWith<IllegalStateException> { executor.execute(command) }
        useCase.failure = null
        val next = executor.execute(command)

        assertIs<AiStorySummaryStarted>(next)
        assertEquals(2, useCase.commands.size)
    }
}
