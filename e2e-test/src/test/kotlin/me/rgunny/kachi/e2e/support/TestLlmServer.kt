package me.rgunny.kachi.e2e.support

import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenAI 호환 chat completions API를 흉내 내는 LLM provider stub.
 *
 * `/chat/completions` 한 path만 다룬다.
 * 응답은 요청 본문(user 메시지의 기사 제목)에 든 [StoryMarker]로 고르므로 테스트끼리 응답을 나눠 갖지 않는다.
 * 표지에 정한 응답이 없으면 [respondSummary]의 기본값으로 만든 요약을 돌려준다.
 * 요약은 ai-service가 파싱하는 JSON(title·content·sentiment·developmentKind)을 content 문자열에 담는다.
 */
class TestLlmServer : AutoCloseable {
    private val server = TestStubServer()
    private val jsonMapper = JsonMapper.builder().build()
    private val responses = ConcurrentHashMap<StoryMarker, TestStubResponse>()

    val port: Int get() = server.port

    init {
        server.route(CHAT_COMPLETIONS_PATH) { request ->
            StoryMarker.findIn(request.body)?.let { responses[it] } ?: summary()
        }
    }

    /** [marker] story의 요약 요청에 이 요약을 돌려준다. */
    fun respondSummary(
        marker: StoryMarker,
        title: String = "요약 제목",
        content: String = "요약 본문",
        sentiment: String = "NEUTRAL",
        developmentKind: String = "DEVELOPMENT"
    ) {
        responses[marker] = summary(title, content, sentiment, developmentKind)
    }

    /** [marker] story의 요약 요청을 400으로 거절한다. ai-service는 이것을 입력 탓 실패로 분류한다. */
    fun rejectRequests(marker: StoryMarker) {
        responses[marker] = TestStubResponse(statusCode = 400, body = """{"error":{"message":"bad request"}}""")
    }

    /** [marker]가 든 요청 본문을 받은 순서대로 돌려준다. */
    fun requestsFor(marker: StoryMarker): List<String> {
        return server.requests(CHAT_COMPLETIONS_PATH).map { it.body }.filter { StoryMarker.findIn(it) == marker }
    }

    override fun close() {
        server.close()
    }

    private fun summary(
        title: String = "요약 제목",
        content: String = "요약 본문",
        sentiment: String = "NEUTRAL",
        developmentKind: String = "DEVELOPMENT"
    ): TestStubResponse {
        val summaryJson = jsonMapper.writeValueAsString(
            mapOf("title" to title, "content" to content, "sentiment" to sentiment, "developmentKind" to developmentKind)
        )
        val body = jsonMapper.writeValueAsString(
            mapOf(
                "model" to "test-model",
                "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to summaryJson))),
                "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 20)
            )
        )
        return TestStubResponse(statusCode = 200, body = body)
    }

    companion object {
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    }
}
