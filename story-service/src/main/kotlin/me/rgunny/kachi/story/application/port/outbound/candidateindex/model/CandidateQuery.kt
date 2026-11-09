package me.rgunny.kachi.story.application.port.outbound.candidateindex.model

import java.time.Instant
import me.rgunny.kachi.story.domain.ArticleLanguage
import me.rgunny.kachi.story.domain.Embedding

/**
 * 후보 검색 조건.
 */
data class CandidateQuery(
    val embedding: Embedding,
    val collectedAfter: Instant,
    val limit: Int,
    val language: ArticleLanguage? = null
) {
    init {
        require(limit >= 1) { "후보 수는 1 이상이어야 합니다: $limit" }
    }
}
