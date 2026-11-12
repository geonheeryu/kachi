package me.rgunny.kachi.ai.adapter.outbound.llm.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import me.rgunny.kachi.ai.adapter.outbound.llm.LlmHttpExceptionClassifier
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderPort
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmGenerationMetadata
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmKeywordExpansionResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryPlan
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmStorySummaryResult
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreparedLlmStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.PreviousStorySummary
import me.rgunny.kachi.ai.application.port.outbound.llm.model.StorySummaryArticle
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.keyword.ExpandedKeyword
import me.rgunny.kachi.ai.domain.llm.KeywordExpansionPrompt
import me.rgunny.kachi.ai.domain.llm.LlmFailure
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmPrompt
import me.rgunny.kachi.ai.domain.llm.StorySummaryPrompt
import me.rgunny.kachi.ai.domain.llm.TokenUsage
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * OpenAI chat completions 규격으로 모델 하나를 부르는 adapter.
 *
 * 프롬프트를 system·user 메시지로 옮기고, 응답에서 content와 모델 이름을 꺼내고, HTTP status를 [LlmFailureCode]로 옮긴다.
 * 어느 제공자의 어느 모델을 부르는지는 [model]이, 주소·인증·timeout은 [webClient]가 정한다.
 * JSON 모드(`response_format`)는 항상 켠다.
 * 호출 실패는 전부 [LlmProviderException]이며 재시도는 하지 않는다.
 */
class OpenAiChatAdapter(
    private val webClient: WebClient,
    private val jsonMapper: JsonMapper,
    private val model: LlmModel
) : LlmProviderPort {

    override fun prepareStorySummary(): PreparedLlmStorySummary {
        return OpenAiPreparedStorySummary(
            plan = LlmStorySummaryPlan(
                provider = model.provider,
                promptVersion = StorySummaryPrompt.version
            ),
            adapter = this
        )
    }

    override suspend fun expandKeyword(
        keyword: AiKeyword,
        maxExpansions: Int
    ): LlmKeywordExpansionResult {
        val response = requestChatCompletion(
            prompt = KeywordExpansionPrompt,
            input = KeywordExpansionPrompt.input(keyword, maxExpansions)
        )

        val expandedKeywords = parseKeywordExpansion(response.firstContent())
            .map(ExpandedKeyword::of)
            .take(maxExpansions)

        if (expandedKeywords.isEmpty()) {
            throw invalidResponse("LLM keyword expansion response is empty")
        }

        return LlmKeywordExpansionResult(
            expandedKeywords = expandedKeywords,
            metadata = metadata(response, KeywordExpansionPrompt)
        )
    }

    override suspend fun summarizeStory(
        keywords: List<AiKeyword>,
        previousSummary: PreviousStorySummary?,
        articles: List<StorySummaryArticle>
    ): LlmStorySummaryResult {
        require(keywords.isNotEmpty()) { "story summary keywords are required" }
        require(articles.isNotEmpty()) { "story summary articles are required" }

        val response = requestChatCompletion(
            prompt = StorySummaryPrompt,
            input = StorySummaryPrompt.input(
                keywords = keywords,
                previousSummary = previousSummary?.let {
                    StorySummaryPrompt.PreviousSummary(title = it.title, content = it.content)
                },
                articles = articles.map(::promptStoryArticle)
            )
        )
        val parsed = parseStorySummary(response.firstContent())

        return LlmStorySummaryResult(
            title = parsed.title.trim(),
            content = parsed.content.trim(),
            sentiment = parsed.sentiment(),
            developmentKind = parsed.developmentKind()
                ?: throw invalidResponse("LLM story summary developmentKind is missing or unknown"),
            metadata = metadata(response, StorySummaryPrompt)
        )
    }

    /**
     * 프롬프트의 system 메시지와 JSON으로 직렬화한 [input]을 user 메시지로 보낸다.
     */
    private suspend fun requestChatCompletion(
        prompt: LlmPrompt,
        input: Any
    ): OpenAiChatResponse {
        val options = OpenAiRequestOptions.from(model.options)
        val userPrompt = jsonMapper.writeValueAsString(input)

        return try {
            webClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .bodyValue(
                    OpenAiChatRequest(
                        model = model.code,
                        messages = listOf(
                            OpenAiChatMessage(role = "system", content = prompt.system),
                            OpenAiChatMessage(role = "user", content = userPrompt)
                        ),
                        max_tokens = prompt.maxTokens,
                        response_format = OpenAiResponseFormat.JSON_OBJECT,
                        reasoning_effort = options.reasoningEffort,
                        thinking = options.thinking
                    )
                )
                // status와 Retry-After 헤더를 함께 읽는 exchange(retrieve()는 헤더 접근 불가)
                .exchangeToMono { response ->
                    if (response.statusCode().isError) {
                        errorResponse(response)
                    } else {
                        response.bodyToMono<OpenAiChatResponse>()
                    }
                }
                .awaitSingle()
        } catch (exception: CancellationException) {
            // coroutine 취소(provider 장애 아님)
            throw exception
        } catch (exception: LlmProviderException) {
            throw exception
        } catch (exception: Exception) {
            throw transportException(exception)
        }
    }

    /**
     * 오류 응답의 status와 header를 body보다 먼저 읽어 분류에 사용한다.
     */
    private fun errorResponse(response: ClientResponse): Mono<OpenAiChatResponse> {
        val statusCode = response.statusCode().value()
        val retryAfterMillis = retryAfterMillis(response.headers().asHttpHeaders())

        return response.bodyToMono<String>()
            .defaultIfEmpty("")
            .flatMap { body ->
                Mono.error<OpenAiChatResponse>(httpException(statusCode, retryAfterMillis, body))
            }
    }

    private fun httpException(
        statusCode: Int,
        retryAfterMillis: Long?,
        body: String
    ): LlmProviderException {
        val detail = "status=$statusCode, body=${body.take(MAX_ERROR_BODY_LENGTH)}"
        // 요청 본문(400·413·422), 모델(404), 계정(401·402·403)
        val code = when (statusCode) {
            HTTP_BAD_REQUEST, HTTP_PAYLOAD_TOO_LARGE, HTTP_UNPROCESSABLE_CONTENT -> LlmFailureCode.LLM_REQUEST_REJECTED
            HTTP_UNAUTHORIZED -> LlmFailureCode.LLM_UNAUTHORIZED
            HTTP_PAYMENT_REQUIRED -> LlmFailureCode.LLM_PAYMENT_REQUIRED
            HTTP_FORBIDDEN -> LlmFailureCode.LLM_FORBIDDEN
            HTTP_NOT_FOUND -> LlmFailureCode.LLM_MODEL_NOT_FOUND
            HTTP_TOO_MANY_REQUESTS -> LlmFailureCode.LLM_RATE_LIMITED
            in HTTP_SERVER_ERROR_RANGE -> LlmFailureCode.LLM_SERVER_ERROR
            else -> LlmFailureCode.LLM_UNKNOWN_ERROR
        }

        return LlmProviderException(
            failure(
                code = code,
                message = "${code.defaultMessage}. $detail",
                statusCode = statusCode,
                retryAfterMillis = retryAfterMillis.takeIf { code == LlmFailureCode.LLM_RATE_LIMITED }
            )
        )
    }

    /**
     * 응답을 받지 못했거나 읽지 못한 실패를 분류한다.
     *
     * timeout은 예외 체인 전체에서 찾는다(최상위 타입만으로는 판별 불가).
     */
    private fun transportException(exception: Exception): LlmProviderException {
        val code = when {
            LlmHttpExceptionClassifier.isTimeout(exception) -> LlmFailureCode.LLM_TIMEOUT
            exception is WebClientRequestException -> LlmFailureCode.LLM_NETWORK_ERROR
            // body 해석 실패(provider 계약 위반, 재시도 없음)
            else -> LlmFailureCode.LLM_INVALID_RESPONSE
        }

        return LlmProviderException(
            failure = failure(
                code = code,
                message = exception.message?.takeIf { it.isNotBlank() } ?: code.defaultMessage
            ),
            cause = exception
        )
    }

    private fun retryAfterMillis(headers: HttpHeaders): Long? {
        return headers.getFirst(HttpHeaders.RETRY_AFTER)
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.let { it * MILLIS_PER_SECOND }
    }

    private fun failure(
        code: LlmFailureCode,
        message: String = code.defaultMessage,
        statusCode: Int? = null,
        retryAfterMillis: Long? = null
    ): LlmFailure {
        return LlmFailure(
            code = code,
            provider = model.provider,
            message = message,
            statusCode = statusCode,
            retryAfterMillis = retryAfterMillis
        )
    }

    private fun invalidResponse(message: String): LlmProviderException {
        return LlmProviderException(failure(code = LlmFailureCode.LLM_INVALID_RESPONSE, message = message))
    }

    private fun OpenAiChatResponse.firstContent(): String {
        return choices.firstOrNull()
            ?.message
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw invalidResponse("LLM response content is empty")
    }

    private fun parseKeywordExpansion(content: String): List<String> {
        val jsonObject = extractJsonObject(content, "LLM keyword expansion response must contain a JSON object")
        val parsed = runCatching {
            jsonMapper
                .readerFor(ParsedKeywordExpansion::class.java)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue<ParsedKeywordExpansion>(jsonObject)
        }.getOrElse { throw invalidResponse("LLM keyword expansion response is not a valid JSON object") }

        return parsed.keywords
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun promptStoryArticle(article: StorySummaryArticle): StorySummaryPrompt.Article {
        return StorySummaryPrompt.Article(
            source = article.source,
            title = article.title,
            excerpt = article.excerpt,
            publishedAt = article.publishedAt
        )
    }

    private fun parseStorySummary(content: String): ParsedStorySummary {
        val jsonObject = extractJsonObject(content, "LLM story summary response must contain a JSON object")
        val parsed = runCatching {
            jsonMapper
                .readerFor(ParsedStorySummary::class.java)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue<ParsedStorySummary>(jsonObject)
        }.getOrElse { throw invalidResponse("LLM story summary response is not a valid JSON object") }

        if (parsed.title.isBlank()) {
            throw invalidResponse("LLM story summary title is empty")
        }
        if (parsed.content.isBlank()) {
            throw invalidResponse("LLM story summary content is empty")
        }

        return parsed
    }

    /**
     * content에서 첫 `{`부터 마지막 `}`까지를 JSON 객체로 잘라 낸다.
     *
     * 일부 provider는 JSON 모드에서도 markdown code fence나 설명 문장을 덧붙인다.
     * 없으면 [missingMessage]로 [LlmProviderException]을 던진다.
     */
    private fun extractJsonObject(
        content: String,
        missingMessage: String
    ): String {
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')

        if (startIndex !in 0..<endIndex) {
            throw invalidResponse(missingMessage)
        }

        return content.substring(startIndex, endIndex + 1)
    }

    private fun metadata(
        response: OpenAiChatResponse,
        prompt: LlmPrompt
    ): LlmGenerationMetadata {
        return LlmGenerationMetadata(
            provider = model.provider,
            requestedModel = model.code,
            // 응답이 보고한 모델 우선(요청 code와 다를 수 있음)
            model = response.model?.takeIf { it.isNotBlank() } ?: model.code,
            promptVersion = prompt.version,
            tokenUsage = TokenUsage(
                inputTokens = response.usage?.prompt_tokens ?: 0,
                outputTokens = response.usage?.completion_tokens ?: 0
            )
        )
    }

    companion object {
        /** chat completions 생성 endpoint 경로. */
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_UNPROCESSABLE_CONTENT = 422
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SERVER_ERROR_RANGE = 500..599
        private const val MAX_ERROR_BODY_LENGTH = 500
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
