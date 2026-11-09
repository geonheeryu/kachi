package me.rgunny.kachi.collector.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import me.rgunny.kachi.collector.adapter.outbound.news.finnhub.FinnhubNewsProvider
import me.rgunny.kachi.collector.adapter.outbound.news.finnhub.FinnhubNewsSettings
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

@Configuration
@EnableConfigurationProperties(FinnhubNewsProperties::class)
class FinnhubNewsConfig {

    /**
     * Finnhub News API 호출 전용 WebClient를 구성한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.collector.providers.finnhub",
        name = ["enabled"],
        havingValue = "true"
    )
    fun finnhubNewsWebClient(
        properties: FinnhubNewsProperties
    ): WebClient {
        validateProperties(properties)

        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            // 외부 API 호출이 수집 실행 전체를 오래 붙잡지 않도록 provider별 timeout을 적용한다.
            .clientConnector(ReactorClientHttpConnector(finnhubNewsHttpClient(properties)))
            // JSON body를 DTO로 역직렬화하므로 provider별 메모리 버퍼 상한을 둔다.
            .codecs { it.defaultCodecs().maxInMemorySize(properties.maxInMemorySize) }
            .build()
    }

    /**
     * Finnhub News provider를 application 출력 포트 구현체로 등록한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.collector.providers.finnhub",
        name = ["enabled"],
        havingValue = "true"
    )
    fun finnhubNewsProvider(
        @Qualifier("finnhubNewsWebClient")
        finnhubNewsWebClient: WebClient,
        properties: FinnhubNewsProperties,
        clock: Clock
    ): FinnhubNewsProvider {
        return FinnhubNewsProvider(
            webClient = finnhubNewsWebClient,
            settings = FinnhubNewsSettings(
                companyNewsPath = properties.companyNewsPath,
                apiKey = properties.apiKey,
                lookbackDays = properties.lookbackDays
            ),
            clock = clock
        )
    }

    /**
     * Finnhub API는 credential이 없으면 모든 호출이 실패하므로 bean 생성 시점에 설정 오류를 드러낸다.
     */
    private fun validateProperties(properties: FinnhubNewsProperties) {
        require(properties.apiKey.isNotBlank()) { "Finnhub News provider apiKey is required" }
        require(properties.baseUrl.isNotBlank()) { "Finnhub News provider baseUrl is required" }
        require(properties.companyNewsPath.isNotBlank()) { "Finnhub News provider companyNewsPath is required" }
        require(properties.lookbackDays > 0) { "Finnhub News provider lookbackDays must be positive" }
        require(!properties.connectTimeout.isNegative && !properties.connectTimeout.isZero) {
            "Finnhub News provider connectTimeout must be positive"
        }
        require(!properties.responseTimeout.isNegative && !properties.responseTimeout.isZero) {
            "Finnhub News provider responseTimeout must be positive"
        }
        require(!properties.readTimeout.isNegative && !properties.readTimeout.isZero) {
            "Finnhub News provider readTimeout must be positive"
        }
        require(!properties.writeTimeout.isNegative && !properties.writeTimeout.isZero) {
            "Finnhub News provider writeTimeout must be positive"
        }
        require(properties.maxInMemorySize > 0) { "Finnhub News provider maxInMemorySize must be positive" }
    }

    /**
     * Reactor Netty 기반 HTTP client에 연결/응답/read/write timeout을 적용한다.
     */
    private fun finnhubNewsHttpClient(properties: FinnhubNewsProperties): HttpClient {
        return HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout.toMillis().toInt())
            .responseTimeout(properties.responseTimeout)
            .doOnConnected { connection ->
                connection
                    .addHandlerLast(readTimeoutHandler(properties))
                    .addHandlerLast(writeTimeoutHandler(properties))
            }
    }

    /**
     * 연결 후 응답 body read가 멈췄을 때 대기 시간을 제한한다.
     */
    private fun readTimeoutHandler(properties: FinnhubNewsProperties): ReadTimeoutHandler {
        return ReadTimeoutHandler(properties.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 요청 write가 멈췄을 때 대기 시간을 제한한다.
     */
    private fun writeTimeoutHandler(properties: FinnhubNewsProperties): WriteTimeoutHandler {
        return WriteTimeoutHandler(properties.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
