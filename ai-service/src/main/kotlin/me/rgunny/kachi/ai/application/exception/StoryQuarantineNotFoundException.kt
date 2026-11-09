package me.rgunny.kachi.ai.application.exception

import me.rgunny.kachi.ai.domain.story.StoryId

/**
 * 해제 대상 story 격리 기록이 없을 때의 실패.
 */
class StoryQuarantineNotFoundException(
    storyId: StoryId
) : AiException(
    errorCode = AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_FOUND,
    message = messageOf(
        AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_FOUND,
        "storyId=${storyId.value}"
    )
)
