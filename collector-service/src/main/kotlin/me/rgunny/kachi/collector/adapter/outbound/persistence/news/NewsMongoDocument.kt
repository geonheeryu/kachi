package me.rgunny.kachi.collector.adapter.outbound.persistence.news

import java.time.Instant
import java.util.UUID
import me.rgunny.kachi.collector.domain.News
import me.rgunny.kachi.collector.domain.NewsSource
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/**
 * `news` 컬렉션 문서. 도메인 `News`와 1:1이고 `urlHash`는 정규화 URL의 해시다.
 */
@Document(collection = "news")
@CompoundIndex(
    name = "uk_news_source_url_hash",
    def = "{'source': 1, 'urlHash': 1}",
    unique = true
)
data class NewsMongoDocument(
    @Id
    val id: UUID,
    val source: NewsSource,
    val title: String,
    val excerpt: String,
    val url: String,
    val urlHash: String,
    val language: String,
    val publishedAt: Instant,
    val collectedAt: Instant,
    val matchedKeywords: List<String>
) {
    companion object {
        fun fromDomain(news: News): NewsMongoDocument {
            return NewsMongoDocument(
                id = news.id.value,
                source = news.source,
                title = news.title.value,
                excerpt = news.excerpt.value,
                url = news.url.value,
                urlHash = news.urlHash,
                language = news.language.value,
                publishedAt = news.publishedAt,
                collectedAt = news.collectedAt,
                matchedKeywords = news.matchedKeywords.map { it.value }
            )
        }
    }
}
