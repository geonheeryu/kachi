package me.rgunny.kachi.ai.adapter.inbound.keyword

import me.rgunny.kachi.ai.application.port.inbound.keyword.ExpandKeywordsUseCase
import me.rgunny.kachi.ai.application.port.inbound.keyword.model.ExpandKeywordsCommand
import me.rgunny.kachi.ai.application.port.outbound.lock.model.AiExecutionLock
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import org.springframework.stereotype.Component

/**
 * scheduler와 internal API에서 들어온 키워드 확장 요청을 받아 중복 실행을 막고 유스케이스를 호출하는 executor.
 *
 * 무엇을 보호하는지는 [AiExecutionLock.KEYWORD_EXPANSION]이 말하고, 그 규칙을 무엇이 지키는지는 알지 않는다.
 * 여기서는 lock의 결과를 확장 요청의 결과로 옮기는 일만 한다.
 */
@Component
class AiKeywordExpansionExecutor(
    private val expandKeywordsUseCase: ExpandKeywordsUseCase,
    private val executionLock: ExecutionLockPort
) {
    suspend fun execute(command: ExpandKeywordsCommand): AiKeywordExpansionExecutionResult {
        // 1. lock을 얻은 요청만 확장 유스케이스를 실행한다. 해제는 포트가 보장한다.
        val outcome = executionLock.withLock(AiExecutionLock.KEYWORD_EXPANSION) {
            expandKeywordsUseCase.expand(command)
        }

        // 2. lock의 결과를 이 진입점의 언어로 옮긴다.
        return when (outcome) {
            is ExecutionLockOutcome.Executed ->
                AiKeywordExpansionStarted(outcome.value)

            is ExecutionLockOutcome.AlreadyHeld ->
                AiKeywordExpansionAlreadyRunning(
                    runningExpansion = RunningAiKeywordExpansion(startedAt = outcome.holder.acquiredAt)
                )

            is ExecutionLockOutcome.Unavailable ->
                AiKeywordExpansionLockUnavailable(outcome.cause)
        }
    }
}
