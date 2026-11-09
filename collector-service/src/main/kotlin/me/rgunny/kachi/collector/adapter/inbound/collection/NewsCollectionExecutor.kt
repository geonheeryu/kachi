package me.rgunny.kachi.collector.adapter.inbound.collection

import me.rgunny.kachi.collector.application.port.inbound.collection.CollectNewsUseCase
import me.rgunny.kachi.collector.application.port.inbound.collection.model.CollectNewsCommand
import me.rgunny.kachi.collector.application.port.outbound.lock.model.CollectorExecutionLock
import me.rgunny.kachi.collector.application.port.outbound.lock.model.ExecutionLockOutcome
import me.rgunny.kachi.collector.application.port.outbound.lock.ExecutionLockPort
import org.springframework.stereotype.Component

/**
 * scheduler와 internal API에서 들어온 뉴스 수집 요청을 받아 중복 실행을 막고 수집 유스케이스를 호출하는 executor.
 *
 * 무엇을 보호하는지는 [CollectorExecutionLock.NEWS_COLLECTION]이 말하고, 그 규칙을 무엇이 지키는지는 알지 않는다.
 * 여기서는 lock의 결과를 수집 요청의 결과로 옮기는 일만 한다.
 */
@Component
class NewsCollectionExecutor(
    private val collectNewsUseCase: CollectNewsUseCase,
    private val executionLock: ExecutionLockPort
) {
    suspend fun execute(command: CollectNewsCommand): NewsCollectionExecutionResult {
        // 1. lock을 얻은 요청만 수집 유스케이스를 실행한다. 해제는 포트가 보장한다.
        val outcome = executionLock.withLock(CollectorExecutionLock.NEWS_COLLECTION) {
            collectNewsUseCase.collect(command)
        }

        // 2. lock의 결과를 이 진입점의 언어로 옮긴다.
        return when (outcome) {
            is ExecutionLockOutcome.Executed ->
                NewsCollectionExecutionResult.Started(outcome.value)

            is ExecutionLockOutcome.AlreadyHeld ->
                NewsCollectionExecutionResult.AlreadyRunning(
                    runningCollection = RunningNewsCollection(startedAt = outcome.holder.acquiredAt)
                )

            is ExecutionLockOutcome.Unavailable ->
                NewsCollectionExecutionResult.LockUnavailable(outcome.cause)
        }
    }
}
