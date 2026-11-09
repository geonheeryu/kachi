package me.rgunny.kachi.collector.domain

import java.time.Instant

/**
 * provider에서 받은 기사 한 건.
 *
 * 저장 후 바뀌지 않는 사실이며, 같은 출처의 같은 페이지는 `urlHash`로 한 건만 남는다.
 * 모든 속성이 있어야 기사다. 제목·발췌문·URL·언어·발행 시각 중 하나라도 없는 item은 adapter가 기사로 만들지 않는다.
 * 기사들 사이의 관계(같은 사건인가)는 이 컨텍스트가 판단하지 않는다.
 */
class News private constructor(
    val id: NewsId,
    val source: NewsSource,
    val title: NewsTitle,
    val excerpt: NewsExcerpt,
    val url: NewsUrl,
    val urlHash: String,
    val language: NewsLanguage,
    val publishedAt: Instant,
    val collectedAt: Instant,
    val matchedKeywords: List<CollectedKeyword>
) {
    companion object {

        fun create(
            source: NewsSource,
            title: NewsTitle,
            excerpt: NewsExcerpt,
            url: NewsUrl,
            language: NewsLanguage,
            publishedAt: Instant,
            collectedAt: Instant,
            matchedKeywords: List<CollectedKeyword>
        ): News {
            require(matchedKeywords.isNotEmpty()) { "매칭 키워드는 하나 이상이어야 합니다" }

            return News(
                id = NewsId.newId(),
                source = source,
                title = title,
                excerpt = excerpt,
                url = url,
                urlHash = url.hash,
                language = language,
                publishedAt = publishedAt,
                collectedAt = collectedAt,
                matchedKeywords = matchedKeywords.distinct()
            )
        }
    }
}
