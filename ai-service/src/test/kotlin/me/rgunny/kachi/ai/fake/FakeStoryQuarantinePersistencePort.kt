package me.rgunny.kachi.ai.fake

import me.rgunny.kachi.ai.application.port.outbound.quarantine.StoryQuarantinePersistencePort
import me.rgunny.kachi.ai.domain.outbox.AiOutbox
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantine
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.domain.story.StoryId

/**
 * story 격리 기록을 메모리에 두는 fake.
 *
 * [quarantinedOutboxes]는 격리 전이와 함께 남긴 발행 대기 이벤트의 기록이다.
 */
class FakeStoryQuarantinePersistencePort : StoryQuarantinePersistencePort {

    val quarantines = mutableMapOf<StoryId, StoryQuarantine>()
    val quarantinedOutboxes = mutableListOf<AiOutbox>()

    override suspend fun findByStoryId(storyId: StoryId): StoryQuarantine? = quarantines[storyId]

    override suspend fun findAll(status: StoryQuarantineStatus?): List<StoryQuarantine> {
        return quarantines.values.filter { status == null || it.status == status }
    }

    override suspend fun save(quarantine: StoryQuarantine): StoryQuarantine {
        quarantines[quarantine.storyId] = quarantine

        return quarantine
    }

    override suspend fun saveQuarantined(quarantine: StoryQuarantine, outbox: AiOutbox): StoryQuarantine {
        quarantines[quarantine.storyId] = quarantine
        quarantinedOutboxes += outbox

        return quarantine
    }
}
