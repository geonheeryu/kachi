package me.rgunny.kachi.ai.adapter.inbound.keyword

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.port.inbound.keyword.model.ExpandKeywordsCommand
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.fake.BlockingExpandKeywordsUseCase
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.fake.FakeExecutionLockPort
import me.rgunny.kachi.ai.fake.RecordingExpandKeywordsUseCase
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@DisplayName("AiKeywordExpansionExecutor")
class AiKeywordExpansionExecutorTest {

    @Test
    @DisplayName("lock을 확인할 수 없으면 키워드 확장을 실행하지 않는다")
    fun doNotExpandWhenLockIsUnavailable() = runBlocking {
        val useCase = RecordingExpandKeywordsUseCase()
        val cause = IllegalStateException("lock 저장소 장애")
        val executor = AiKeywordExpansionExecutor(
            useCase,
            FakeExecutionLockPort(ExecutionLockOutcome.Unavailable(cause))
        )

        val result = executor.execute(ExpandKeywordsCommand(keywords = listOf(AiKeyword.of("NVIDIA"))))

        val unavailable = assertIs<AiKeywordExpansionLockUnavailable>(result)
        assertEquals(cause, unavailable.cause)
        assertEquals(0, useCase.invokeCount)
    }

    @Test
    @DisplayName("실행 중인 키워드 확장이 있으면 중복 요청을 건너뛴다")
    fun skipWhenKeywordExpansionIsAlreadyRunning() = runBlocking {
        val useCase = BlockingExpandKeywordsUseCase()
        val executor = AiKeywordExpansionExecutor(useCase, AiTestFixture.executionLock())
        val command = ExpandKeywordsCommand(
            keywords = listOf(AiKeyword.of("NVIDIA"))
        )

        val firstExecution = async {
            executor.execute(command)
        }
        useCase.started.await()

        val secondExecution = executor.execute(command)
        useCase.release.complete(Unit)

        assertIs<AiKeywordExpansionAlreadyRunning>(secondExecution)
        assertEquals(AiTestFixture.NOW, secondExecution.runningExpansion.startedAt)
        assertIs<AiKeywordExpansionStarted>(firstExecution.await())
        assertEquals(1, useCase.executeCount)
    }

}
