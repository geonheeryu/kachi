package me.rgunny.kachi.ai.application.exception

/**
 * story 격리 운영 에러 코드.
 *
 * 해제 요청이 대상 기록을 찾지 못했거나, 찾은 기록이 해제할 수 있는 상태가 아닐 때 쓴다.
 */
enum class AiStoryQuarantineErrorCode(
    override val code: String,
    override val message: String
) : AiErrorCode {
    STORY_QUARANTINE_NOT_FOUND(
        code = "STORY_QUARANTINE_NOT_FOUND",
        message = "story 격리 기록이 없습니다"
    ),
    STORY_QUARANTINE_NOT_RELEASABLE(
        code = "STORY_QUARANTINE_NOT_RELEASABLE",
        message = "격리 상태가 아닌 story는 해제할 수 없습니다"
    )
}
