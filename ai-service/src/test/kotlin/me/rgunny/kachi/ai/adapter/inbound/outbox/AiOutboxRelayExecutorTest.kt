package me.rgunny.kachi.ai.adapter.inbound.outbox

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.port.inbound.outbox.RelayAiOutboxUseCase
import me.rgunny.kachi.ai.fake.BlockingRelayAiOutboxUseCase
import me.rgunny.kachi.ai.fake.FailingRelayAiOutboxUseCase
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.fake.FakeExecutionLockPort
import me.rgunny.kachi.ai.fake.RecordingRelayAiOutboxUseCase
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@DisplayName("AiOutboxRelayExecutor")
class AiOutboxRelayExecutorTest {

    @Test
    @DisplayName("실행 중인 relay가 있으면 중복 요청을 건너뛴다")
    fun skipWhenRelayIsAlreadyRunning() = runBlocking {
        val useCase = BlockingRelayAiOutboxUseCase()
        val executor = executorOf(useCase)

        val firstExecution = async {
            executor.execute()
        }
        useCase.started.await()

        val secondExecution = executor.execute()
        useCase.release.complete(Unit)

        assertIs<AiOutboxRelayAlreadyRunning>(secondExecution)
        assertEquals(AiTestFixture.NOW, secondExecution.runningRelay.startedAt)
        assertIs<AiOutboxRelayFinished>(firstExecution.await())
        assertEquals(1, useCase.executeCount)
    }

    @Test
    @DisplayName("실행 결과를 그대로 감싸 돌려준다")
    fun wrapRelayResult() = runBlocking {
        val useCase = RecordingRelayAiOutboxUseCase()

        val execution = executorOf(useCase).execute()

        assertEquals(useCase.result, assertIs<AiOutboxRelayFinished>(execution).result)
    }

    @Test
    @DisplayName("실행이 끝나면 다음 요청을 받을 수 있도록 lock을 돌려준다")
    fun releaseLockAfterExecution() = runBlocking {
        val useCase = RecordingRelayAiOutboxUseCase()
        val executor = executorOf(useCase)

        executor.execute()
        executor.execute()

        assertEquals(2, useCase.invokeCount)
    }

    @Test
    @DisplayName("실행이 실패해도 lock을 돌려준다")
    fun releaseLockAfterFailure() = runBlocking {
        val useCase = FailingRelayAiOutboxUseCase()
        val executor = executorOf(useCase)

        assertFailsWith<IllegalStateException> { executor.execute() }
        assertFailsWith<IllegalStateException> { executor.execute() }

        assertEquals(2, useCase.invokeCount)
    }

    @Test
    @DisplayName("lock을 확인할 수 없으면 relay를 실행하지 않는다")
    fun doNotRelayWhenLockIsUnavailable() = runBlocking {
        val useCase = RecordingRelayAiOutboxUseCase()
        val cause = IllegalStateException("lock 저장소 장애")
        val executor = AiOutboxRelayExecutor(useCase, FakeExecutionLockPort(ExecutionLockOutcome.Unavailable(cause)))

        val result = executor.execute()

        val unavailable = assertIs<AiOutboxRelayLockUnavailable>(result)
        assertEquals(cause, unavailable.cause)
        assertEquals(0, useCase.invokeCount)
    }

    private fun executorOf(useCase: RelayAiOutboxUseCase): AiOutboxRelayExecutor {
        return AiOutboxRelayExecutor(useCase, AiTestFixture.executionLock())
    }
}
