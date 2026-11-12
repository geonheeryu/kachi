package me.rgunny.kachi.ai.application.port.outbound.quarantine

import me.rgunny.kachi.ai.domain.outbox.AiOutbox
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantine
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.domain.story.StoryId

/**
 * story 격리 기록 저장소 출력 포트.
 *
 * story마다 기록은 한 건이다.
 */
interface StoryQuarantinePersistencePort {

    /**
     * story 하나의 기록을 읽는다.
     *
     * null은 실패한 적 없는 story다.
     */
    suspend fun findByStoryId(storyId: StoryId): StoryQuarantine?

    suspend fun findAll(status: StoryQuarantineStatus?): List<StoryQuarantine>

    /**
     * 격리 기록을 저장한다.
     *
     * 기존 기록의 갱신은 새 기록으로 쌓이지 않고 그 기록을 대체한다.
     */
    suspend fun save(quarantine: StoryQuarantine): StoryQuarantine

    /**
     * 격리 전이와 발행 대기 이벤트를 한 트랜잭션으로 저장한다.
     */
    suspend fun saveQuarantined(quarantine: StoryQuarantine, outbox: AiOutbox): StoryQuarantine
}
