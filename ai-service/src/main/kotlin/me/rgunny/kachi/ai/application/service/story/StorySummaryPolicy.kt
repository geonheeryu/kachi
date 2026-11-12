package me.rgunny.kachi.ai.application.service.story

import me.rgunny.kachi.ai.domain.llm.StorySummaryPrompt
import me.rgunny.kachi.ai.domain.story.AiStory
import java.time.Duration
import java.time.Instant

/**
 * story 요약 트리거의 판정 기준.
 *
 * 즉시 트리거는 미요약 기사 수가 [minNewArticles]에 닿았는가로, tick 트리거는 기준 시각이 [maxWait]를 넘겼는가로 본다.
 */
class StorySummaryPolicy(
    val minNewArticles: Int,
    val maxWait: Duration,
    val maxArticlesPerVersion: Int
) {
    init {
        require(minNewArticles >= 1) { "min-new-articles는 1 이상이어야 합니다: $minNewArticles" }
        require(!maxWait.isNegative && !maxWait.isZero) { "max-wait는 0보다 커야 합니다" }
        require(maxArticlesPerVersion in 1..StorySummaryPrompt.MAX_ARTICLES) {
            "max-articles-per-version은 1 이상 ${StorySummaryPrompt.MAX_ARTICLES} 이하여야 합니다: $maxArticlesPerVersion"
        }
    }

    /**
     * 기사 기록 직후 그 자리에서 요약할 만큼 미요약이 쌓였는가.
     */
    fun requiresImmediateSummary(story: AiStory): Boolean {
        return !story.merged && story.pendingCount >= minNewArticles
    }

    /**
     * tick이 요약 대상을 찾을 때 쓰는 기준 시각.
     */
    fun dueThreshold(now: Instant): Instant {
        return now.minus(maxWait)
    }

    /**
     * 기준 시각이 maxWait를 넘긴 미요약 story인가(저장소 due 조회와 같은 조건).
     */
    fun isDue(story: AiStory, now: Instant): Boolean {
        if (story.merged || story.pendingCount < 1) {
            return false
        }
        val baseline = story.summaryWaitBaseline ?: return false

        return !baseline.isAfter(dueThreshold(now))
    }
}
