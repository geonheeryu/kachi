package me.rgunny.kachi.collector.adapter.outbound.news.google

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.NewsSource
import me.rgunny.kachi.collector.config.GoogleNewsConfig
import me.rgunny.kachi.collector.config.GoogleNewsProperties
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.test.assertEquals

@DisplayName("GoogleNewsRssProvider")
class GoogleNewsRssProviderTest {

    private val properties = GoogleNewsProperties(
        enabled = true,
        baseUrl = "https://news.google.com",
        rssSearchPath = "/rss/search",
        languageCode = "ko",
        countryCode = "KR",
        connectTimeout = java.time.Duration.ofSeconds(2),
        responseTimeout = java.time.Duration.ofSeconds(5),
        readTimeout = java.time.Duration.ofSeconds(5),
        writeTimeout = java.time.Duration.ofSeconds(5),
        maxInMemorySize = 512 * 1024,
    )

    private val keyword = CollectedKeyword.of("NVIDIA")

    @Nested
    @DisplayName("collect()")
    inner class Collect {

        @Test
        @DisplayName("Google RSS XML을 수집 기사 목록으로 변환한다")
        fun collectGoogleRssItems() = runBlocking {
            val provider = providerOf(
                """
                <rss>
                  <channel>
                    <item>
                      <title>NVIDIA 실적 발표</title>
                      <link>https://kachi.com/news/1</link>
                      <description>&lt;a href="https://kachi.com/news/1"&gt;NVIDIA 실적 발표&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color="#6f6f6f"&gt;Kachi News&lt;/font&gt;</description>
                      <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
                      <source>Kachi News</source>
                    </item>
                    <item>
                      <title>NVIDIA 신제품 공개</title>
                      <link>https://kachi.com/news/2</link>
                      <description>&lt;ol&gt;&lt;li&gt;&lt;a href="https://kachi.com/news/2"&gt;NVIDIA 신제품 공개&lt;/a&gt;&lt;/li&gt;&lt;li&gt;&lt;a href="https://kachi.com/news/3"&gt;엔비디아 신형 GPU&lt;/a&gt;&lt;/li&gt;&lt;/ol&gt;</description>
                      <pubDate>Wed, 27 May 2026 11:00:00 GMT</pubDate>
                      <source>Kachi News</source>
                    </item>
                  </channel>
                </rss>
                """.trimIndent()
            )

            val articles = provider.collect(keyword)

            assertEquals(2, articles.size)
            assertEquals(NewsSource.GOOGLE, articles[0].source)
            assertEquals("NVIDIA 실적 발표", articles[0].title)
            assertEquals("https://kachi.com/news/1", articles[0].url)
            assertEquals(Instant.parse("2026-05-27T10:00:00Z"), articles[0].publishedAt)
            assertEquals("NVIDIA 실적 발표 Kachi News", articles[0].excerpt)
            assertEquals("NVIDIA 신제품 공개엔비디아 신형 GPU", articles[1].excerpt)
            assertEquals("ko", articles[0].language)
        }

        @Test
        @DisplayName("title, link, description 중 하나라도 비어 있는 RSS item은 제외한다")
        fun skipItemsWithoutRequiredFields() = runBlocking {
            val provider = providerOf(
                """
                <rss>
                  <channel>
                    <item>
                      <title>NVIDIA 정상 뉴스</title>
                      <link>https://kachi.com/news/1</link>
                      <description>정상 설명</description>
                      <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
                    </item>
                    <item>
                      <title></title>
                      <link>https://kachi.com/news/2</link>
                      <description>제목 없음</description>
                      <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
                    </item>
                    <item>
                      <title>NVIDIA 링크 없음</title>
                      <description>링크 없음</description>
                      <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
                    </item>
                    <item>
                      <title>NVIDIA 설명 없음</title>
                      <link>https://kachi.com/news/4</link>
                      <pubDate>Wed, 27 May 2026 10:00:00 GMT</pubDate>
                    </item>
                  </channel>
                </rss>
                """.trimIndent()
            )

            val articles = provider.collect(keyword)

            assertEquals(1, articles.size)
            assertEquals("NVIDIA 정상 뉴스", articles.first().title)
        }

        @Test
        @DisplayName("pubDate를 파싱할 수 없는 RSS item은 제외한다")
        fun skipItemWhenPubDateCannotBeParsed() = runBlocking {
            val provider = providerOf(
                """
                <rss>
                  <channel>
                    <item>
                      <title>NVIDIA 날짜 형식 오류</title>
                      <link>https://kachi.com/news/1</link>
                      <description>설명</description>
                      <pubDate>not-a-date</pubDate>
                    </item>
                  </channel>
                </rss>
                """.trimIndent()
            )

            val articles = provider.collect(keyword)

            assertEquals(0, articles.size)
        }
    }

    private fun providerOf(xml: String): GoogleNewsRssProvider {
        return GoogleNewsConfig().googleNewsRssProvider(
            googleNewsWebClient = webClientReturning(xml),
            properties = properties
        )
    }

    private fun webClientReturning(body: String): WebClient {
        val exchangeFunction = ExchangeFunction {
            Mono.just(
                ClientResponse.create(HttpStatus.OK)
                    .body(body)
                    .build()
            )
        }

        return WebClient.builder()
            .exchangeFunction(exchangeFunction)
            .build()
    }
}
