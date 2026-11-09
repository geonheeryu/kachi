package me.rgunny.kachi.collector.adapter.outbound.news.finnhub

import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.collector.application.port.outbound.news.model.CollectedArticle
import me.rgunny.kachi.collector.application.port.outbound.news.NewsProviderPort
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.NewsSource
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class FinnhubNewsProvider(
    private val webClient: WebClient,
    private val settings: FinnhubNewsSettings,
    private val clock: Clock
) : NewsProviderPort {

    override val source: NewsSource = NewsSource.FINNHUB

    override suspend fun collect(keyword: CollectedKeyword): List<CollectedArticle> {
        val to = LocalDate.now(clock)
        val from = to.minusDays(settings.lookbackDays)
        val symbol = keyword.value.trim().uppercase()

        // 1. Finnhub company-news endpoint를 JSON으로 호출한다.
        val response = webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path(settings.companyNewsPath)
                    .queryParam("symbol", symbol)
                    .queryParam("from", from)
                    .queryParam("to", to)
                    .build()
            }
            .header(FINNHUB_TOKEN_HEADER, settings.apiKey)
            .retrieve()
            .bodyToMono(Array<FinnhubNewsItem>::class.java)
            .awaitSingle()

        // 2. headline과 URL이 없는 item은 adapter 안에서 제외한다.
        return response.mapNotNull { toCollectedArticleOrNull(it) }
    }

    /**
     * 제목·URL·발췌문·발행 시각 중 하나라도 없는 item은 제외한다. 도메인 News는 넷을 모두 요구한다.
     */
    private fun toCollectedArticleOrNull(item: FinnhubNewsItem): CollectedArticle? {
        val title = item.headline.orEmpty().trim()
        val url = item.url.orEmpty().trim()
        val excerpt = item.summary.orEmpty().trim()
        val publishedAt = parsePublishedAt(item.datetime) ?: return null

        if (title.isBlank() || url.isBlank() || excerpt.isBlank()) {
            return null
        }

        return CollectedArticle(
            source = NewsSource.FINNHUB,
            title = title,
            excerpt = excerpt,
            url = url,
            language = LANGUAGE,
            publishedAt = publishedAt
        )
    }

    /**
     * Finnhub datetime은 UNIX timestamp 초 단위다. 값이 없거나 epoch 이전이면 null이고 그 item은 제외된다.
     */
    private fun parsePublishedAt(datetime: Long?): Instant? {
        if (datetime == null || datetime < 0) return null

        return Instant.ofEpochSecond(datetime)
    }

    private companion object {
        // Finnhub company news는 영문 기사만 준다.
        private const val LANGUAGE = "en"
        private const val FINNHUB_TOKEN_HEADER = "X-Finnhub-Token"
    }
}
