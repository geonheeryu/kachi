package me.rgunny.kachi.ai.adapter.inbound.scheduler

import ch.qos.logback.classic.Level
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.adapter.inbound.story.AiStorySummaryExecutor
import me.rgunny.kachi.ai.application.port.inbound.story.SummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesCommand
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesResult
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.fake.BlockingSummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.fake.FakeExecutionLockPort
import me.rgunny.kachi.ai.fake.RecordingSummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.RecordingLogAppender
import me.rgunny.kachi.ai.config.AiSchedulerConfig
import me.rgunny.kachi.ai.config.AiStorySummarySchedulerProperties
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiStorySummaryScheduler")
class AiStorySummarySchedulerTest {

    private val useCase = RecordingSummarizeDueStoriesUseCase()

    private fun scheduler(
        properties: AiStorySummarySchedulerProperties = properties(),
        useCase: SummarizeDueStoriesUseCase = this.useCase,
        executionLock: ExecutionLockPort = AiTestFixture.executionLock()
    ): AiStorySummaryScheduler {
        return AiStorySummaryScheduler(
            executor = AiStorySummaryExecutor(useCase, executionLock),
            settings = AiSchedulerConfig().aiStorySummarySchedulerSettings(properties)
        )
    }

    private fun properties(
        enabled: Boolean = true,
        maxStoriesPerTick: Int = 20
    ): AiStorySummarySchedulerProperties {
        return AiStorySummarySchedulerProperties(
            enabled = enabled,
            fixedDelay = Duration.ofMinutes(5),
            initialDelay = Duration.ofMinutes(1),
            maxStoriesPerTick = maxStoriesPerTick
        )
    }

    @Test
    @DisplayName("scheduler가 비활성화되어 있으면 tick을 실행하지 않는다")
    fun skipWhenDisabled() = runBlocking {
        scheduler(properties(enabled = false)).summarizeDueStories()

        assertEquals(0, useCase.commands.size)
    }

    @Test
    @DisplayName("tick 상한을 명령으로 전달한다")
    fun passMaxStoriesPerTick() = runBlocking {
        scheduler(properties(maxStoriesPerTick = 7)).summarizeDueStories()

        assertEquals(7, useCase.commands.single().maxStories)
    }

    @Test
    @DisplayName("tick 실행이 실패해도 예외를 전파하지 않고 warn 로그를 남긴다")
    fun swallowExecutionFailure() = runBlocking {
        useCase.failure = IllegalStateException("mongo down")

        val logs = summarizeWithLogs(scheduler(), Level.WARN)

        assertEquals(1, useCase.commands.size)
        assertEquals(listOf("Scheduled story summary failed"), logs)
    }

    @Test
    @DisplayName("tick이 끝나면 결과 집계를 info 로그로 남긴다")
    fun logTickSummaryWhenFinished() = runBlocking {
        useCase.result = SummarizeDueStoriesResult(due = 3, created = 2, skipped = 1, failed = 0, aborted = false)

        val logs = summarizeWithLogs(scheduler(), Level.INFO)

        assertEquals(
            listOf("Scheduled story summary finished: due=3, created=2, skipped=1, failed=0, aborted=false"),
            logs
        )
    }

    @Test
    @DisplayName("이미 실행 중인 tick이 있으면 실패로 보지 않고 이번 tick만 건너뛴다")
    fun logSkipWhenAnotherTickIsRunning() = runBlocking {
        val blocking = BlockingSummarizeDueStoriesUseCase()
        val scheduler = scheduler(useCase = blocking)

        val running = async { scheduler.summarizeDueStories() }
        blocking.started.await()

        val logs = summarizeWithLogs(scheduler, Level.INFO)
        blocking.release.complete(Unit)
        running.await()

        assertEquals(1, blocking.executeCount)
        assertEquals(1, logs.filter { it.startsWith("Skip scheduled story summary because another tick is running") }.size)
    }

    @Test
    @DisplayName("lock을 확인할 수 없으면 tick을 건너뛰고 warn 로그를 남긴다")
    fun logWarnWhenLockIsUnavailable() = runBlocking {
        val lock = FakeExecutionLockPort(ExecutionLockOutcome.Unavailable(IllegalStateException("lock 저장소 장애")))

        val logs = summarizeWithLogs(scheduler(executionLock = lock), Level.WARN)

        assertEquals(0, useCase.commands.size)
        assertEquals(listOf("Skip scheduled story summary because the execution lock could not be checked"), logs)
    }

    @Test
    @DisplayName("tick 상한이 허용 범위를 벗어나면 설정을 만들 수 없다")
    fun rejectInvalidMaxStories() {
        assertFailsWith<IllegalArgumentException> { properties(maxStoriesPerTick = 0) }
        assertFailsWith<IllegalArgumentException> {
            properties(maxStoriesPerTick = SummarizeDueStoriesCommand.MAX_STORIES_PER_TICK + 1)
        }
    }

    /**
     * tick 하나를 돌리고 그 동안 [level]로 찍힌 로그 메시지를 돌려준다.
     */
    private suspend fun summarizeWithLogs(scheduler: AiStorySummaryScheduler, level: Level): List<String> {
        return RecordingLogAppender.attachTo(AiStorySummaryScheduler::class).use { appender ->
            scheduler.summarizeDueStories()
            appender.messagesAt(level)
        }
    }
}
