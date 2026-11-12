package me.rgunny.kachi.notification.routing.application.port.inbound.routing.model

import java.time.Instant

/**
 * story 요약 버전 1건의 라우팅 입력 모델.
 *
 * keywords는 story에 붙은 canonicalKey 1~[MAX_KEYWORDS]개이고 원소는 non-blank다.
 * version은 1 이상이고 summaryId, storyId, title은 non-blank이며 sourceNewsCount는 0 이상이다.
 * 본문은 수신자별로 서비스가 렌더링한다.
 */
data class RouteSummaryCommand(
    val summaryId: String,
    val storyId: String,
    val version: Long,
    val keywords: List<String>,
    val title: String,
    val content: String,
    val sentiment: SummarySentiment,
    val sourceNewsCount: Int,
    val createdAt: Instant,
) {
    init {
        require(summaryId.isNotBlank()) { "summaryId must not be blank" }
        require(storyId.isNotBlank()) { "storyId must not be blank" }
        require(version >= 1) { "version must be positive. version=$version" }
        require(keywords.size in 1..MAX_KEYWORDS) { "keywords size must be in 1..$MAX_KEYWORDS. size=${keywords.size}" }
        require(keywords.none { it.isBlank() }) { "keywords must not contain blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(sourceNewsCount >= 0) { "sourceNewsCount must not be negative" }
    }

    companion object {
        /** user-service 구독 수신자 조회가 한 요청에 받는 키워드 상한. */
        const val MAX_KEYWORDS = 100
    }
}
