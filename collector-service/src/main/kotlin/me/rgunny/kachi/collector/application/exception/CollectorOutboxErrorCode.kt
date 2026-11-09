package me.rgunny.kachi.collector.application.exception

/**
 * outbox 이벤트 발행과 운영 개입의 에러 코드.
 *
 * 재시도 여부는 코드가 아니라 [CollectorOutboxPublishException.retryable]이 가르고, 코드는 그 실패가 무엇이었는지를 로그와 운영 조회에 남긴다.
 */
enum class CollectorOutboxErrorCode(
    override val code: String,
    override val message: String
) : CollectorErrorCode {
    OUTBOX_PUBLISH_FAILED(
        code = "OUTBOX_PUBLISH_FAILED",
        message = "outbox 이벤트 발행에 실패했습니다"
    ),
    OUTBOX_NOT_FOUND(
        code = "OUTBOX_NOT_FOUND",
        message = "outbox 행이 없습니다"
    ),
    OUTBOX_NOT_RECOVERABLE(
        code = "OUTBOX_NOT_RECOVERABLE",
        message = "DEAD 상태의 outbox만 복구할 수 있습니다"
    )
}
