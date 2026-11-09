package me.rgunny.kachi.collector.adapter.outbound.news.naver

import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.collector.application.port.outbound.news.model.CollectedArticle
import me.rgunny.kachi.collector.application.port.outbound.news.NewsProviderPort
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.NewsSource
import org.springframework.web.reactive.function.client.WebClient
import me.rgunny.kachi.collector.adapter.outbound.news.HtmlText
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class NaverNewsSearchProvider(
    private val webClient: WebClient,
    private val settings: NaverNewsSettings
) : NewsProviderPort {

    override val source: NewsSource = NewsSource.NAVER

    override suspend fun collect(keyword: CollectedKeyword): List<CollectedArticle> {
        // 1. Naver Search API 뉴스 검색 endpoint를 JSON으로 호출한다.
        val response = webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path(settings.newsSearchPath)
                    .queryParam("query", keyword.value)
                    .queryParam("display", settings.display)
                    .queryParam("start", settings.start)
                    .queryParam("sort", settings.sort)
                    .build()
            }
            .header(NAVER_CLIENT_ID_HEADER, settings.clientId)
            .header(NAVER_CLIENT_SECRET_HEADER, settings.clientSecret)
            .retrieve()
            .bodyToMono(NaverNewsSearchResponse::class.java)
            .awaitSingle()

        // 2. 제목·URL·발췌문·발행 시각 중 하나라도 없는 item은 adapter 안에서 제외한다. 도메인 News는 넷을 모두 요구한다.
        return response.items.orEmpty()
            .mapNotNull { toCollectedArticleOrNull(it) }
    }

    private fun toCollectedArticleOrNull(item: NaverNewsItem): CollectedArticle? {
        val title = HtmlText.toPlain(item.title.orEmpty())
        // 원문 URL(originallink)을 우선 저장하고, 없으면 Naver 뉴스 URL(link)을 사용한다.
        val url = item.originallink.orEmpty().ifBlank { item.link.orEmpty() }.trim()
        val excerpt = HtmlText.toPlain(item.description.orEmpty())
        val publishedAt = parsePublishedAt(item.pubDate.orEmpty()) ?: return null

        if (title.isBlank() || url.isBlank() || excerpt.isBlank()) {
            return null
        }

        return CollectedArticle(
            source = NewsSource.NAVER,
            title = title,
            excerpt = excerpt,
            url = url,
            language = LANGUAGE,
            publishedAt = publishedAt
        )
    }

    /**
     * Naver Search API pubDate를 Instant로 변환한다. 파싱할 수 없으면 null이고 그 item은 제외된다.
     */
    private fun parsePublishedAt(pubDate: String): Instant? {
        if (pubDate.isBlank()) return null

        return runCatching {
            ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()
    }

    private companion object {
        // Naver 뉴스 검색은 한국어 기사만 준다.
        private const val LANGUAGE = "ko"
        private const val NAVER_CLIENT_ID_HEADER = "X-Naver-Client-Id"
        private const val NAVER_CLIENT_SECRET_HEADER = "X-Naver-Client-Secret"
    }
}
