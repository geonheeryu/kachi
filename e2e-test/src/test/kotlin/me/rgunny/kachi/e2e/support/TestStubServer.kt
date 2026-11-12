package me.rgunny.kachi.e2e.support

import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 테스트 대상이 HTTP로 부르는 외부 vendor를 대신하는 테스트 JVM 안의 서버.
 *
 * 같은 JVM은 [baseUrl]로, 컨테이너 안의 서비스는 `host.testcontainers.internal:[port]`로 닿는다.
 * path마다 요청 내용으로 응답을 고르는 [route]나 고정 응답 [respond]를 둔다.
 * 둘 다 없는 path는 404다.
 * 받은 요청은 [requests]에 path별로 남는다.
 */
class TestStubServer : AutoCloseable {
    private val routes = ConcurrentHashMap<String, (TestStubRequest) -> TestStubResponse>()
    private val recorded = ConcurrentHashMap<String, MutableList<TestStubRequest>>()

    private val server: DisposableServer = HttpServer.create()
        // OS가 고르는 빈 포트
        .port(0)
        .handle { request, response ->
            val decoder = QueryStringDecoder(request.uri())
            request.receive().aggregate().asString().defaultIfEmpty("")
                .map { body ->
                    TestStubRequest(
                        path = decoder.path(),
                        queryParameters = decoder.parameters().mapValues { (_, values) -> values.first() },
                        body = body
                    )
                }
                .flatMap { stubRequest ->
                    recorded.getOrPut(stubRequest.path) { Collections.synchronizedList(mutableListOf()) } += stubRequest
                    val stubResponse = routes[stubRequest.path]?.invoke(stubRequest) ?: NOT_FOUND
                    response.status(HttpResponseStatus.valueOf(stubResponse.statusCode))
                    stubResponse.headers.forEach { (name, value) -> response.header(name, value) }
                    response.header(HttpHeaderNames.CONTENT_TYPE, stubResponse.contentType)
                    response.sendString(Mono.just(stubResponse.body)).then()
                }
        }
        .bindNow()

    val port: Int = server.port()
    val baseUrl: String = "http://localhost:$port"

    /** [path]의 요청마다 [responder]가 응답을 만든다. */
    fun route(path: String, responder: (TestStubRequest) -> TestStubResponse) {
        routes[path] = responder
    }

    /** [path]에 늘 같은 응답을 돌려준다. */
    fun respond(path: String, response: TestStubResponse) {
        route(path) { response }
    }

    /** [path]로 받은 요청을 받은 순서대로 돌려준다. */
    fun requests(path: String): List<TestStubRequest> {
        val requests = recorded[path] ?: return emptyList()
        return synchronized(requests) { requests.toList() }
    }

    override fun close() {
        server.disposeNow()
    }

    private companion object {
        val NOT_FOUND = TestStubResponse(statusCode = 404, body = "{}")
    }
}
