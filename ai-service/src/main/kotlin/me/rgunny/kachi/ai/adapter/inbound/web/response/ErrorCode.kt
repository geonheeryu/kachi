package me.rgunny.kachi.ai.adapter.inbound.web.response

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String
) {
    LLM_CALL_FAILED(HttpStatus.BAD_GATEWAY, "LLM 호출에 실패했습니다"),
    INVALID_KEYWORD_EXPANSION_REQUEST(HttpStatus.BAD_REQUEST, "키워드 확장 요청이 올바르지 않습니다"),
    KEYWORD_EXPANSION_ALREADY_RUNNING(HttpStatus.CONFLICT, "키워드 확장이 이미 실행 중입니다"),
    KEYWORD_EXPANSION_LOCK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실행 lock을 확인할 수 없어 키워드 확장을 시작하지 못했습니다"),
    STORY_SUMMARY_ALREADY_RUNNING(HttpStatus.CONFLICT, "story 요약 tick이 이미 실행 중입니다"),
    STORY_SUMMARY_LOCK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실행 lock을 확인할 수 없어 story 요약 tick을 시작하지 못했습니다"),
    STORY_QUARANTINE_NOT_FOUND(HttpStatus.NOT_FOUND, "story 격리 기록을 찾을 수 없습니다"),
    STORY_QUARANTINE_NOT_RELEASABLE(HttpStatus.CONFLICT, "격리 상태가 아닌 story는 해제할 수 없습니다"),
    AI_OUTBOX_NOT_FOUND(HttpStatus.NOT_FOUND, "outbox 행을 찾을 수 없습니다"),
    AI_OUTBOX_NOT_RECOVERABLE(HttpStatus.CONFLICT, "DEAD 상태가 아닌 outbox는 복구할 수 없습니다"),
    LLM_MODEL_NOT_CANDIDATE(HttpStatus.NOT_FOUND, "어느 용도의 후보도 아닌 LLM 모델입니다"),
    INVALID_INTERNAL_REQUEST(HttpStatus.BAD_REQUEST, "내부 API 요청이 올바르지 않습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다")
}
