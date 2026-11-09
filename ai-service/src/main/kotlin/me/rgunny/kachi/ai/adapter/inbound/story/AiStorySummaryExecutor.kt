package me.rgunny.kachi.ai.adapter.inbound.story

import me.rgunny.kachi.ai.application.port.inbound.story.SummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesCommand
import me.rgunny.kachi.ai.application.port.outbound.lock.model.AiExecutionLock
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import org.springframework.stereotype.Component

/**
 * scheduler와 internal API에서 들어온 story 요약 tick 요청을 받아 [AiExecutionLock.STORY_SUMMARY]로 중복 실행을 막고 유스케이스를 호출하는 executor.
 */
@Component
class AiStorySummaryExecutor(
    private val summarizeDueStoriesUseCase: SummarizeDueStoriesUseCase,
    private val executionLock: ExecutionLockPort
) {
    suspend fun execute(command: SummarizeDueStoriesCommand): AiStorySummaryExecutionResult {
        // 1. lock을 얻은 요청만 유스케이스를 실행한다.
        val outcome = executionLock.withLock(AiExecutionLock.STORY_SUMMARY) {
            summarizeDueStoriesUseCase.summarizeDue(command)
        }

        // 2. lock의 결과를 이 진입점의 언어로 옮긴다.
        return when (outcome) {
            is ExecutionLockOutcome.Executed ->
                AiStorySummaryStarted(outcome.value)

            is ExecutionLockOutcome.AlreadyHeld ->
                AiStorySummaryAlreadyRunning(
                    runningSummary = RunningAiStorySummary(startedAt = outcome.holder.acquiredAt)
                )

            is ExecutionLockOutcome.Unavailable ->
                AiStorySummaryLockUnavailable(outcome.cause)
        }
    }
}
