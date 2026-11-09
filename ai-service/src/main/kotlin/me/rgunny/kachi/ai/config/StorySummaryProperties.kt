package me.rgunny.kachi.ai.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * story 요약 트리거 설정.
 *
 * 값 검증은 이 값들로 만들어지는 [me.rgunny.kachi.ai.application.service.story.StorySummaryPolicy]가 맡는다.
 */
@ConfigurationProperties(prefix = StorySummaryProperties.PREFIX)
data class StorySummaryProperties(
    val minNewArticles: Int,
    val maxWait: Duration,
    val maxArticlesPerVersion: Int
) {
    companion object {
        const val PREFIX = "kachi.ai.story-summary"
    }
}
