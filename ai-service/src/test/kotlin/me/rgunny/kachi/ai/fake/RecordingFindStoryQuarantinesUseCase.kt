package me.rgunny.kachi.ai.fake

import me.rgunny.kachi.ai.application.port.inbound.quarantine.FindStoryQuarantinesUseCase
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.FindStoryQuarantinesQuery
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.FindStoryQuarantinesResult
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.StoryQuarantineSnapshot

/**
 * 전달받은 조회 조건을 그대로 보관하는 story 격리 조회 유스케이스 fake.
 */
class RecordingFindStoryQuarantinesUseCase : FindStoryQuarantinesUseCase {
    var invokeCount = 0
    var lastQuery: FindStoryQuarantinesQuery? = null
    var quarantines: List<StoryQuarantineSnapshot> = emptyList()

    override suspend fun find(query: FindStoryQuarantinesQuery): FindStoryQuarantinesResult {
        invokeCount += 1
        lastQuery = query

        return FindStoryQuarantinesResult(quarantines)
    }
}
