package me.rgunny.kachi.ai.application.exception

import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.domain.story.StoryId

/**
 * 격리 상태가 아닌 story 기록을 해제하려 했을 때의 실패.
 */
class StoryQuarantineNotReleasableException(
    storyId: StoryId,
    status: StoryQuarantineStatus
) : AiException(
    errorCode = AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_RELEASABLE,
    message = messageOf(
        AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_RELEASABLE,
        "storyId=${storyId.value}, status=$status"
    )
)
