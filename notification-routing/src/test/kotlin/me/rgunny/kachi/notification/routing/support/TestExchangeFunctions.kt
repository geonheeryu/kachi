package me.rgunny.kachi.notification.routing.support

import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Mono
import java.time.Duration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 외부 서비스 호출 adapter 테스트에서 실제 네트워크 없이 응답을 고정하는 ExchangeFunction.
 */
fun jsonExchangeFunction(
    responseBody: String,
    status: HttpStatus = HttpStatus.OK,
    delay: Duration = Duration.ZERO,
): ExchangeFunction {
    return ExchangeFunction {
        Mono.just(
            ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(responseBody)
                .build()
        ).delayElement(delay)
    }
}

/**
 * 고정 응답을 돌려주면서 보낸 요청과 본문을 보관하는 ExchangeFunction.
 *
 * 요청 본문은 mock 요청에 써 넣은 뒤 문자열로 꺼낸다.
 */
class CapturingExchangeFunction(
    private val body: String,
    private val status: HttpStatus = HttpStatus.OK,
) : ExchangeFunction {

    lateinit var request: ClientRequest
    private var sentBodyText: String = ""

    /** 보낸 요청 본문을 JSON으로 읽은 것. 본문이 없었으면 빈 객체다. */
    val sentBody: JsonNode
        get() = JSON.readTree(sentBodyText.ifBlank { "{}" })

    override fun exchange(request: ClientRequest): Mono<ClientResponse> {
        this.request = request
        val sink = MockClientHttpRequest(request.method(), request.url())

        return request.writeTo(sink, ExchangeStrategies.withDefaults())
            .then(Mono.defer { sink.bodyAsString })
            .doOnNext { sentBodyText = it }
            .then(
                Mono.fromSupplier {
                    ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()
                }
            )
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
