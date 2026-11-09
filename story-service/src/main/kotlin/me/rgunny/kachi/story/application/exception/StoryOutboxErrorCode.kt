package me.rgunny.kachi.story.application.exception

enum class StoryOutboxErrorCode(
    override val code: String,
    override val message: String
) : StoryErrorCode {
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
