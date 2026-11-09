package me.rgunny.kachi.collector.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import me.rgunny.kachi.collector.adapter.outbound.news.google.GoogleNewsRssProvider
import me.rgunny.kachi.collector.adapter.outbound.news.google.GoogleNewsSettings
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
@EnableConfigurationProperties(GoogleNewsProperties::class)
class GoogleNewsConfig {

    /**
     * Google News RSS 호출 전용 WebClient를 구성한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.collector.providers.google",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun googleNewsWebClient(
        properties: GoogleNewsProperties
    ): WebClient {
        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            // 외부 API 호출이 수집 실행 전체를 오래 붙잡지 않도록 provider별 timeout을 적용한다.
            .clientConnector(ReactorClientHttpConnector(googleNewsHttpClient(properties)))
            // RSS XML body를 문자열로 받으므로 provider별 메모리 버퍼 상한을 둔다.
            .codecs { it.defaultCodecs().maxInMemorySize(properties.maxInMemorySize) }
            .build()
    }

    /**
     * Google News RSS provider를 application 출력 포트 구현체로 등록한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.collector.providers.google",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun googleNewsRssProvider(
        @Qualifier("googleNewsWebClient")
        googleNewsWebClient: WebClient,
        properties: GoogleNewsProperties
    ): GoogleNewsRssProvider {
        return GoogleNewsRssProvider(
            webClient = googleNewsWebClient,
            settings = GoogleNewsSettings(
                rssSearchPath = properties.rssSearchPath,
                languageCode = properties.languageCode,
                countryCode = properties.countryCode
            )
        )
    }

    /**
     * Reactor Netty 기반 HTTP client에 연결/응답/read/write timeout을 적용한다.
     */
    private fun googleNewsHttpClient(properties: GoogleNewsProperties): HttpClient {
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
    private fun readTimeoutHandler(properties: GoogleNewsProperties): ReadTimeoutHandler {
        return ReadTimeoutHandler(properties.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 요청 write가 멈췄을 때 대기 시간을 제한한다
     */
    private fun writeTimeoutHandler(properties: GoogleNewsProperties): WriteTimeoutHandler {
        return WriteTimeoutHandler(properties.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
