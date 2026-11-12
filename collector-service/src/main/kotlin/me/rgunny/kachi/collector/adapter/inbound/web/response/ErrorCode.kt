package me.rgunny.kachi.collector.adapter.inbound.web.response

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    COLLECTION_ALREADY_RUNNING(HttpStatus.CONFLICT, "뉴스 수집이 이미 실행 중입니다"),
    COLLECTION_LOCK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실행 lock을 확인할 수 없어 뉴스 수집을 시작하지 못했습니다"),
    INVALID_NEWS_SOURCE(HttpStatus.BAD_REQUEST, "지원하지 않는 뉴스 provider입니다"),
    NEWS_PROVIDER_NOT_ENABLED(HttpStatus.NOT_FOUND, "활성화된 뉴스 provider가 없습니다"),
    COLLECTOR_OUTBOX_NOT_FOUND(HttpStatus.NOT_FOUND, "outbox 행을 찾을 수 없습니다"),
    COLLECTOR_OUTBOX_NOT_RECOVERABLE(HttpStatus.CONFLICT, "DEAD 상태가 아닌 outbox는 복구할 수 없습니다"),
    INVALID_INTERNAL_REQUEST(HttpStatus.BAD_REQUEST, "내부 API 요청이 올바르지 않습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다")
}
