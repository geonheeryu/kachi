package me.rgunny.kachi.story.application.port.outbound.candidateindex.model

import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.StoryId

/**
 * 검색이 돌려준 후보 기사 하나.
 */
data class CandidateHit(
    val newsId: NewsId,
    val storyId: StoryId,
    val similarity: Double
) {
    init {
        require(similarity in -1.0..1.0) { "코사인 유사도는 -1과 1 사이여야 합니다: $similarity" }
    }
}
