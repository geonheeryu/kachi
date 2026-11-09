package me.rgunny.kachi.collector.adapter.inbound.outbox

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.application.port.inbound.outbox.RelayCollectorOutboxUseCase
import me.rgunny.kachi.collector.fake.BlockingRelayCollectorOutboxUseCase
import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.collector.fake.FailingRelayCollectorOutboxUseCase
import me.rgunny.kachi.collector.fake.FakeExecutionLockPort
import me.rgunny.kachi.collector.fake.RecordingRelayCollectorOutboxUseCase
import me.rgunny.kachi.collector.fixture.CollectorTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@DisplayName("CollectorOutboxRelayExecutor")
class CollectorOutboxRelayExecutorTest {

    @Test
    @DisplayName("실행 중인 relay가 있으면 중복 요청을 건너뛴다")
    fun skipWhenRelayIsAlreadyRunning() = runBlocking {
        val useCase = BlockingRelayCollectorOutboxUseCase()
        val executor = executorOf(useCase)

        val firstExecution = async {
            executor.execute()
        }
        useCase.started.await()

        val secondExecution = executor.execute()
        useCase.release.complete(Unit)

        assertIs<CollectorOutboxRelayAlreadyRunning>(secondExecution)
        assertEquals(CollectorTestFixture.NOW, secondExecution.runningRelay.startedAt)
        assertIs<CollectorOutboxRelayFinished>(firstExecution.await())
        assertEquals(1, useCase.executeCount)
    }

    @Test
    @DisplayName("실행 결과를 그대로 감싸 돌려준다")
    fun wrapRelayResult() = runBlocking {
        val useCase = RecordingRelayCollectorOutboxUseCase()

        val execution = executorOf(useCase).execute()

        assertEquals(useCase.result, assertIs<CollectorOutboxRelayFinished>(execution).result)
    }

    @Test
    @DisplayName("실행이 끝나면 다음 요청을 받을 수 있도록 lock을 돌려준다")
    fun releaseLockAfterExecution() = runBlocking {
        val useCase = RecordingRelayCollectorOutboxUseCase()
        val executor = executorOf(useCase)

        executor.execute()
        executor.execute()

        assertEquals(2, useCase.invokeCount)
    }

    @Test
    @DisplayName("실행이 실패해도 lock을 돌려준다")
    fun releaseLockAfterFailure() = runBlocking {
        val useCase = FailingRelayCollectorOutboxUseCase()
        val executor = executorOf(useCase)

        assertFailsWith<IllegalStateException> { executor.execute() }
        assertFailsWith<IllegalStateException> { executor.execute() }

        assertEquals(2, useCase.invokeCount)
    }

    @Test
    @DisplayName("lock을 확인할 수 없으면 relay를 실행하지 않는다")
    fun doNotRelayWhenLockIsUnavailable() = runBlocking {
        val useCase = RecordingRelayCollectorOutboxUseCase()
        val cause = IllegalStateException("lock 저장소 장애")
        val executor = CollectorOutboxRelayExecutor(
            useCase,
            FakeExecutionLockPort(ExecutionLockOutcome.Unavailable(cause))
        )

        val result = executor.execute()

        val unavailable = assertIs<CollectorOutboxRelayLockUnavailable>(result)
        assertEquals(cause, unavailable.cause)
        assertEquals(0, useCase.invokeCount)
    }

    private fun executorOf(useCase: RelayCollectorOutboxUseCase): CollectorOutboxRelayExecutor {
        return CollectorOutboxRelayExecutor(useCase, CollectorTestFixture.executionLock())
    }
}
