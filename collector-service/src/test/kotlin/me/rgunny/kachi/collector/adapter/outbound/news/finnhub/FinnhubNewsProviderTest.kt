package me.rgunny.kachi.collector.adapter.outbound.news.finnhub

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.domain.CollectedKeyword
import me.rgunny.kachi.collector.domain.NewsSource
import me.rgunny.kachi.collector.fixture.CollectorTestFixture
import me.rgunny.kachi.collector.config.FinnhubNewsConfig
import me.rgunny.kachi.collector.config.FinnhubNewsProperties
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.test.assertEquals

@DisplayName("FinnhubNewsProvider")
class FinnhubNewsProviderTest {

    private val properties = FinnhubNewsProperties(
        enabled = true,
        baseUrl = "https://finnhub.io",
        companyNewsPath = "/api/v1/company-news",
        apiKey = "api-key",
        lookbackDays = 7,
        connectTimeout = java.time.Duration.ofSeconds(2),
        responseTimeout = java.time.Duration.ofSeconds(5),
        readTimeout = java.time.Duration.ofSeconds(5),
        writeTimeout = java.time.Duration.ofSeconds(5),
        maxInMemorySize = 512 * 1024,
    )

    private val clock = CollectorTestFixture.CLOCK
    private val keyword = CollectedKeyword.of(" nvda ")

    @Nested
    @DisplayName("collect()")
    inner class Collect {

        @Test
        @DisplayName("Finnhub company news JSON을 수집 기사 목록으로 변환한다")
        fun collectFinnhubNewsItems() = runBlocking {
            val exchange = CapturingExchangeFunction(
                """
                [
                  {
                    "category": "company",
                    "datetime": 1779789600,
                    "headline": "NVIDIA announces earnings",
                    "id": 1,
                    "image": "https://kachi.com/image/1.png",
                    "related": "NVDA",
                    "source": "Finnhub",
                    "summary": "NVIDIA news",
                    "url": "https://kachi.com/news/1"
                  },
                  {
                    "category": "company",
                    "datetime": 1779793200,
                    "headline": "NVIDIA unveils new chip",
                    "id": 2,
                    "image": "https://kachi.com/image/2.png",
                    "related": "NVDA",
                    "source": "Finnhub",
                    "summary": "NVIDIA news",
                    "url": "https://kachi.com/news/2"
                  }
                ]
                """.trimIndent()
            )
            val provider = providerOf(exchange)

            val articles = provider.collect(keyword)

            assertEquals(2, articles.size)
            assertEquals(NewsSource.FINNHUB, articles[0].source)
            assertEquals("NVIDIA announces earnings", articles[0].title)
            assertEquals("https://kachi.com/news/1", articles[0].url)
            assertEquals(Instant.parse("2026-05-26T10:00:00Z"), articles[0].publishedAt)
            assertEquals("NVIDIA news", articles[0].excerpt)
            assertEquals("en", articles[0].language)

            val request = exchange.request
            assertEquals("api-key", request.headers().getFirst("X-Finnhub-Token"))
            assertEquals("/api/v1/company-news", request.url().path)
            assertEquals("symbol=NVDA&from=2026-05-23&to=2026-05-30", request.url().query)
        }

        @Test
        @DisplayName("headline, URL, summary 중 하나라도 비어 있는 item은 제외한다")
        fun skipItemsWithoutRequiredFields() = runBlocking {
            val provider = providerOf(
                CapturingExchangeFunction(
                    """
                    [
                      {
                        "datetime": 1779789600,
                        "headline": "NVIDIA 정상 뉴스",
                        "summary": "정상 요약",
                        "url": "https://kachi.com/news/1"
                      },
                      {
                        "datetime": 1779789600,
                        "headline": "",
                        "summary": "제목 없음",
                        "url": "https://kachi.com/news/2"
                      },
                      {
                        "datetime": 1779789600,
                        "headline": "NVIDIA URL 없음",
                        "summary": "URL 없음",
                        "url": ""
                      },
                      {
                        "datetime": 1779789600,
                        "headline": "NVIDIA 요약 없음",
                        "summary": " ",
                        "url": "https://kachi.com/news/4"
                      }
                    ]
                    """.trimIndent()
                )
            )

            val articles = provider.collect(keyword)

            assertEquals(1, articles.size)
            assertEquals("NVIDIA 정상 뉴스", articles.first().title)
        }

        @Test
        @DisplayName("datetime이 없거나 epoch 이전인 item은 제외한다")
        fun skipItemsWhenDatetimeIsInvalid() = runBlocking {
            val provider = providerOf(
                CapturingExchangeFunction(
                    """
                    [
                      {
                        "datetime": null,
                        "headline": "NVIDIA 날짜 없음",
                        "summary": "요약",
                        "url": "https://kachi.com/news/1"
                      },
                      {
                        "datetime": -1,
                        "headline": "NVIDIA 날짜 오류",
                        "summary": "요약",
                        "url": "https://kachi.com/news/2"
                      }
                    ]
                    """.trimIndent()
                )
            )

            val articles = provider.collect(keyword)

            assertEquals(0, articles.size)
        }
    }

    private fun providerOf(exchangeFunction: ExchangeFunction): FinnhubNewsProvider {
        return FinnhubNewsConfig().finnhubNewsProvider(
            finnhubNewsWebClient = WebClient.builder()
                .baseUrl(properties.baseUrl)
                .exchangeFunction(exchangeFunction)
                .build(),
            properties = properties,
            clock = clock
        )
    }

    private class CapturingExchangeFunction(
        private val body: String
    ) : ExchangeFunction {

        lateinit var request: ClientRequest

        override fun exchange(request: ClientRequest): Mono<ClientResponse> {
            this.request = request

            return Mono.just(
                ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build()
            )
        }
    }
}
