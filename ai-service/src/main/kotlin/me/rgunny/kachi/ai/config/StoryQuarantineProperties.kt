package me.rgunny.kachi.ai.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * story 격리 정책 설정.
 */
@ConfigurationProperties(prefix = StoryQuarantineProperties.PREFIX)
data class StoryQuarantineProperties(
    val failureThreshold: Int
) {
    companion object {
        const val PREFIX = "kachi.ai.story-quarantine"
    }

    init {
        require(failureThreshold >= 1) {
            "격리 임계치는 1 이상이어야 합니다"
        }
    }
}
