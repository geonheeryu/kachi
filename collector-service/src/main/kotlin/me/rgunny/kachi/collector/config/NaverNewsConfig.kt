package me.rgunny.kachi.collector.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import me.rgunny.kachi.collector.adapter.outbound.news.naver.NaverNewsSearchProvider
import me.rgunny.kachi.collector.adapter.outbound.news.naver.NaverNewsSettings
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit

@Configuration
@EnableConfigurationProperties(NaverNewsProperties::class)
class NaverNewsConfig {

    /**
     * Naver Search API 호출 전용 WebClient를 구성한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.collector.providers.naver",
        name = ["enabled"],
        havingValue = "true"
    )
    fun naverNewsWebClient(
        properties: NaverNewsProperties
    ): WebClient {
        validateProperties(properties)

        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            // 외부 API 호출이 수집 실행 전체를 오래 붙잡지 않도록 provider별 timeout을 적용한다.
            .clientConnector(ReactorClientHttpConnector(naverNewsHttpClient(properties)))
            // JSON body를 DTO로 역직렬화하므로 provider별 메모리 버퍼 상한을 둔다.
            .codecs { it.defaultCodecs().maxInMemorySize(properties.maxInMemorySize) }
            .build()
    }

    /**
     * Naver News Search provider를 application 출력 포트 구현체로 등록한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.collector.providers.naver",
        name = ["enabled"],
        havingValue = "true"
    )
    fun naverNewsSearchProvider(
        @Qualifier("naverNewsWebClient")
        naverNewsWebClient: WebClient,
        properties: NaverNewsProperties
    ): NaverNewsSearchProvider {
        return NaverNewsSearchProvider(
            webClient = naverNewsWebClient,
            settings = NaverNewsSettings(
                newsSearchPath = properties.newsSearchPath,
                clientId = properties.clientId,
                clientSecret = properties.clientSecret,
                display = properties.display,
                start = properties.start,
                sort = properties.sort
            )
        )
    }

    /**
     * Naver Search API는 credential이 없으면 모든 호출이 실패하므로 bean 생성 시점에 설정 오류를 드러낸다.
     */
    private fun validateProperties(properties: NaverNewsProperties) {
        require(properties.clientId.isNotBlank()) { "Naver News provider clientId is required" }
        require(properties.clientSecret.isNotBlank()) { "Naver News provider clientSecret is required" }
        require(properties.baseUrl.isNotBlank()) { "Naver News provider baseUrl is required" }
        require(properties.newsSearchPath.isNotBlank()) { "Naver News provider newsSearchPath is required" }
        require(properties.display in 1..100) { "Naver News provider display must be between 1 and 100" }
        require(properties.start in 1..1000) { "Naver News provider start must be between 1 and 1000" }
        require(properties.sort in setOf("sim", "date")) { "Naver News provider sort must be sim or date" }
        require(!properties.connectTimeout.isNegative && !properties.connectTimeout.isZero) {
            "Naver News provider connectTimeout must be positive"
        }
        require(!properties.responseTimeout.isNegative && !properties.responseTimeout.isZero) {
            "Naver News provider responseTimeout must be positive"
        }
        require(!properties.readTimeout.isNegative && !properties.readTimeout.isZero) {
            "Naver News provider readTimeout must be positive"
        }
        require(!properties.writeTimeout.isNegative && !properties.writeTimeout.isZero) {
            "Naver News provider writeTimeout must be positive"
        }
        require(properties.maxInMemorySize > 0) { "Naver News provider maxInMemorySize must be positive" }
    }

    /**
     * Reactor Netty 기반 HTTP client에 연결/응답/read/write timeout을 적용한다.
     */
    private fun naverNewsHttpClient(properties: NaverNewsProperties): HttpClient {
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
    private fun readTimeoutHandler(properties: NaverNewsProperties): ReadTimeoutHandler {
        return ReadTimeoutHandler(properties.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 요청 write가 멈췄을 때 대기 시간을 제한한다.
     */
    private fun writeTimeoutHandler(properties: NaverNewsProperties): WriteTimeoutHandler {
        return WriteTimeoutHandler(properties.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
