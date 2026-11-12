package me.rgunny.kachi.ai.fake

import kotlinx.coroutines.CompletableDeferred
import me.rgunny.kachi.ai.application.port.inbound.story.SummarizeDueStoriesUseCase
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesCommand
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeDueStoriesResult

/**
 * [release]가 완료될 때까지 실행 중 상태를 유지하는 story 요약 tick 유스케이스 fake.
 *
 * [started]는 실행이 시작되면 완료된다.
 */
class BlockingSummarizeDueStoriesUseCase : SummarizeDueStoriesUseCase {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var executeCount = 0

    override suspend fun summarizeDue(command: SummarizeDueStoriesCommand): SummarizeDueStoriesResult {
        executeCount += 1
        started.complete(Unit)
        release.await()

        return SummarizeDueStoriesResult(due = 0, created = 0, skipped = 0, failed = 0, aborted = false)
    }
}
