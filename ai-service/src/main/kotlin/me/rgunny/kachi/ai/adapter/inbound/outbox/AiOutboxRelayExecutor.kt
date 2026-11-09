package me.rgunny.kachi.ai.adapter.inbound.outbox

import me.rgunny.kachi.ai.application.port.inbound.outbox.RelayAiOutboxUseCase
import me.rgunny.kachi.ai.application.port.outbound.lock.model.AiExecutionLock
import me.rgunny.kachi.ai.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * relay 실행 요청을 받아 tick 겹침을 막고 유스케이스를 호출한다.
 *
 * 무엇을 보호하는지와 어디까지 보호하는지는 [AiExecutionLock.OUTBOX_RELAY]가 말한다.
 * 여기서는 lock의 결과를 relay 요청의 결과로 옮기는 일만 한다.
 */
@Component
@ConditionalOnProperty(
    prefix = AiOutboxRelaySettings.PREFIX,
    name = ["enabled"],
    havingValue = "true"
)
class AiOutboxRelayExecutor(
    private val relayUseCase: RelayAiOutboxUseCase,
    private val executionLock: ExecutionLockPort
) {
    suspend fun execute(): AiOutboxRelayExecutionResult {
        // 1. lock을 얻은 요청만 relay 유스케이스를 실행한다. 해제는 포트가 보장한다.
        val outcome = executionLock.withLock(AiExecutionLock.OUTBOX_RELAY) {
            relayUseCase.relay()
        }

        // 2. lock의 결과를 이 진입점의 언어로 옮긴다.
        return when (outcome) {
            is ExecutionLockOutcome.Executed ->
                AiOutboxRelayFinished(outcome.value)

            is ExecutionLockOutcome.AlreadyHeld ->
                AiOutboxRelayAlreadyRunning(
                    runningRelay = RunningAiOutboxRelay(startedAt = outcome.holder.acquiredAt)
                )

            is ExecutionLockOutcome.Unavailable ->
                AiOutboxRelayLockUnavailable(outcome.cause)
        }
    }
}
