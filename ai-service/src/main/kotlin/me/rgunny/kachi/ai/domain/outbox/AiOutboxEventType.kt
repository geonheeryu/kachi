package me.rgunny.kachi.ai.domain.outbox

/**
 * outbox에 기록하는 도메인 이벤트 종류.
 */
enum class AiOutboxEventType {
    SUMMARY_CREATED,
    STORY_SPLIT_REQUESTED,
    STORY_QUARANTINED
}
