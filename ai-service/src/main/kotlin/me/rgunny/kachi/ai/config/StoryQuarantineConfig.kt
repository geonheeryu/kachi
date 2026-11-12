package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.application.service.story.StoryQuarantinePolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * story 격리 설정을 정책 객체로 조립하는 설정.
 */
@Configuration
class StoryQuarantineConfig {

    @Bean
    fun storyQuarantinePolicy(properties: StoryQuarantineProperties): StoryQuarantinePolicy {
        return StoryQuarantinePolicy(failureThreshold = properties.failureThreshold)
    }
}
