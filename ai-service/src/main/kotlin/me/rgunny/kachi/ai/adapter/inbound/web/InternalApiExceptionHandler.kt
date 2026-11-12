package me.rgunny.kachi.ai.adapter.inbound.web

import me.rgunny.kachi.ai.adapter.inbound.web.response.ApiResponse
import me.rgunny.kachi.ai.adapter.inbound.web.response.ErrorCode
import me.rgunny.kachi.ai.application.exception.AiCommonErrorCode
import me.rgunny.kachi.ai.application.exception.AiErrorCode
import me.rgunny.kachi.ai.application.exception.AiException
import me.rgunny.kachi.ai.application.exception.AiOutboxErrorCode
import me.rgunny.kachi.ai.application.exception.AiStoryQuarantineErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

/**
 * 내부 API가 던진 예외를 `ApiResponse` 오류 응답으로 옮기는 예외 핸들러.
 *
 * application 예외는 전부 [AiException]이고 [AiErrorCode]를 가지므로 타입이 아니라 코드로 분기한다.
 * HTTP status는 application이 아니라 web [ErrorCode]가 갖는다.
 */
@RestControllerAdvice
class InternalApiExceptionHandler {

    @ExceptionHandler(AiException::class)
    fun handleAiException(exception: AiException): ResponseEntity<ApiResponse<Unit>> {
        val errorCode = ERROR_CODES[exception.errorCode]

        // 매핑이 없는 실패는 응답 규약을 정하지 않은 경로다. 200으로 새지 않게 500으로 막고 원인을 남긴다.
        if (errorCode == null) {
            log.error("Unmapped internal API failure: code={}", exception.errorCode.code, exception)

            return failure(ErrorCode.INTERNAL_ERROR, message = null)
        }

        return failure(errorCode, exception.message)
    }

    /**
     * enum/UUID처럼 타입이 맞지 않는 파라미터는 WebFlux가 자체 오류 body로 응답한다.
     * 내부 API 응답은 성공/실패가 같은 형식이어야 하므로 여기서 잡아 바꾼다.
     */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleInvalidInput(exception: ServerWebInputException): ResponseEntity<ApiResponse<Unit>> {
        return failure(ErrorCode.INVALID_INTERNAL_REQUEST, exception.reason)
    }

    private fun failure(errorCode: ErrorCode, message: String?): ResponseEntity<ApiResponse<Unit>> {
        return ResponseEntity.status(errorCode.status)
            .body(ApiResponse.failure(errorCode, message))
    }

    private companion object {
        val log = LoggerFactory.getLogger(InternalApiExceptionHandler::class.java)

        val ERROR_CODES: Map<AiErrorCode, ErrorCode> = mapOf(
            AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_FOUND to ErrorCode.STORY_QUARANTINE_NOT_FOUND,
            AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_RELEASABLE to ErrorCode.STORY_QUARANTINE_NOT_RELEASABLE,
            AiOutboxErrorCode.OUTBOX_NOT_FOUND to ErrorCode.AI_OUTBOX_NOT_FOUND,
            AiOutboxErrorCode.OUTBOX_NOT_RECOVERABLE to ErrorCode.AI_OUTBOX_NOT_RECOVERABLE,
            AiCommonErrorCode.LLM_PROVIDER_CALL_FAILED to ErrorCode.LLM_CALL_FAILED
        )
    }
}
