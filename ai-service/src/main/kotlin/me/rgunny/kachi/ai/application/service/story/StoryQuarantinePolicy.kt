package me.rgunny.kachi.ai.application.service.story

/**
 * story를 격리로 넘기는 연속 실패 임계치.
 *
 * `kachi.ai.story-quarantine`
 */
data class StoryQuarantinePolicy(
    val failureThreshold: Int
)
