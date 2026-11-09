package me.rgunny.kachi.collector.adapter.outbound.news.google

import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.collector.adapter.outbound.news.HtmlText
import me.rgunny.kachi.collector.application.port.outbound.news.model.CollectedArticle
import me.rgunny.kachi.collector.application.port.outbound.news.NewsProviderPort
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.NewsSource
import org.springframework.web.reactive.function.client.WebClient
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

class GoogleNewsRssProvider(
    private val webClient: WebClient,
    private val settings: GoogleNewsSettings
) : NewsProviderPort {

    override val source: NewsSource = NewsSource.GOOGLE

    override suspend fun collect(keyword: CollectedKeyword): List<CollectedArticle> {
        // 1. Google 전용 WebClient로 RSS endpoint를 호출하고, 응답 body를 String XML로 받는다.
        val xml = webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path(settings.rssSearchPath)
                    .queryParam("q", keyword.value)
                    .queryParam("hl", settings.languageCode)
                    .queryParam("gl", settings.countryCode)
                    .queryParam("ceid", "${settings.countryCode}:${settings.languageCode}")
                    .build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()

        // 2. parseRss(xml)로 RSS item 목록을 만든다.
        val rssItems = parseRss(xml)

        // 3. 제목·URL·발췌문·발행 시각 중 하나라도 없는 item은 adapter 안에서 제외한다. 도메인 News는 넷을 모두 요구한다.
        return rssItems.mapNotNull { toCollectedArticleOrNull(it) }
    }

    private fun parseRss(xml: String): List<GoogleRssItem> {
        // 1. JDK DocumentBuilderFactory를 만든다.
        // 2. XXE 방지를 위해 외부 entity, DTD 로딩을 비활성화한다.
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false

            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

        // 3. XML 문자열을 Document로 파싱한다.
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        // 4. <item> 노드를 순회하며 title/link/pubDate/source 값을 읽는다.
        val itemNodes = document.getElementsByTagName("item")
        val items = mutableListOf<GoogleRssItem>()

        for (index in 0 until itemNodes.length) {
            val item = itemNodes.item(index) as? Element ?: continue
            val title = item.textContentOf("title") ?: continue
            val link = item.textContentOf("link") ?: continue

            // 5. 필수값 title/link가 없으면 해당 item은 제외한다.
            if (title.isBlank() || link.isBlank()) continue

            items.add(
                GoogleRssItem(
                    title = title,
                    link = link,
                    description = item.textContentOf("description"),
                    pubDate = item.textContentOf("pubDate"),
                    sourceName = item.textContentOf("source")
                )
            )
        }

        return items
    }

    /**
     * Google RSS item을 application 계층이 사용하는 수집 기사 DTO로 변환한다.
     *
     * 발췌문은 RSS description의 평문이다. 기사 하나면 제목과 매체명, 묶인 기사면 관련 제목 목록이 들어 있다.
     * 언어는 요청에 보낸 `hl` 값이다.
     */
    private fun toCollectedArticleOrNull(item: GoogleRssItem): CollectedArticle? {
        val title = item.title.trim()
        val url = item.link.trim()
        val excerpt = HtmlText.toPlain(item.description.orEmpty())
        val publishedAt = parsePublishedAt(item.pubDate) ?: return null

        if (title.isBlank() || url.isBlank() || excerpt.isBlank()) {
            return null
        }

        return CollectedArticle(
            source = NewsSource.GOOGLE,
            title = title,
            excerpt = excerpt,
            url = url,
            language = settings.languageCode,
            publishedAt = publishedAt
        )
    }

    /**
     * Google RSS pubDate를 Instant로 변환한다. 파싱할 수 없으면 null이고 그 item은 제외된다.
     */
    private fun parsePublishedAt(pubDate: String?): Instant? {
        if (pubDate.isNullOrBlank()) return null

        return runCatching {
            ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()
    }

    /**
     * XML element에서 첫 번째 tagName 하위 노드의 textContent를 앞뒤 공백 제거 후 반환한다.
     */
    private fun Element.textContentOf(tagName: String): String? {
        val nodes = getElementsByTagName(tagName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim()
    }

}
