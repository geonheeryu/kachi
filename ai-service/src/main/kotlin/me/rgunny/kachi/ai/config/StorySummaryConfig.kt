package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.application.service.story.StorySummaryPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * story 요약 설정을 정책 객체로 조립하는 설정.
 */
@Configuration
class StorySummaryConfig {

    @Bean
    fun storySummaryPolicy(properties: StorySummaryProperties): StorySummaryPolicy {
        return StorySummaryPolicy(
            minNewArticles = properties.minNewArticles,
            maxWait = properties.maxWait,
            maxArticlesPerVersion = properties.maxArticlesPerVersion
        )
    }
}
