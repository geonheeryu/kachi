package me.rgunny.kachi.ai.adapter.inbound.news

import me.rgunny.kachi.ai.application.port.inbound.news.SummarizeNewsUseCase
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizeNewsCommand
import me.rgunny.kachi.ai.application.port.outbound.lock.model.AiExecutionLock
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import org.springframework.stereotype.Component

/**
 * scheduler와 internal API에서 들어온 뉴스 요약 요청을 받아 중복 실행을 막고 유스케이스를 호출하는 executor.
 *
 * 무엇을 보호하는지는 [AiExecutionLock.NEWS_SUMMARY]가 말하고, 그 규칙을 무엇이 지키는지는 알지 않는다.
 * 여기서는 lock의 결과를 요약 요청의 결과로 옮기는 일만 한다.
 */
@Component
class AiNewsSummaryExecutor(
    private val summarizeNewsUseCase: SummarizeNewsUseCase,
    private val executionLock: ExecutionLockPort
) {
    suspend fun execute(command: SummarizeNewsCommand): AiNewsSummaryExecutionResult {
        // 1. lock을 얻은 요청만 요약 유스케이스를 실행한다. 해제는 포트가 보장한다.
        val outcome = executionLock.withLock(AiExecutionLock.NEWS_SUMMARY) {
            summarizeNewsUseCase.summarize(command)
        }

        // 2. lock의 결과를 이 진입점의 언어로 옮긴다.
        return when (outcome) {
            is ExecutionLockOutcome.Executed ->
                AiNewsSummaryStarted(outcome.value)

            is ExecutionLockOutcome.AlreadyHeld ->
                AiNewsSummaryAlreadyRunning(
                    runningSummary = RunningAiNewsSummary(startedAt = outcome.holder.acquiredAt)
                )

            is ExecutionLockOutcome.Unavailable ->
                AiNewsSummaryLockUnavailable(outcome.cause)
        }
    }
}
