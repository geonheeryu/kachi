package me.rgunny.kachi.ai.fake

import me.rgunny.kachi.ai.application.exception.AiException
import me.rgunny.kachi.ai.application.port.inbound.quarantine.ReleaseStoryQuarantineUseCase
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.ReleaseStoryQuarantineCommand
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.ReleaseStoryQuarantineResult
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.StoryQuarantineSnapshot
import me.rgunny.kachi.ai.fixture.AiTestFixture

/**
 * 전달받은 command를 그대로 보관하는 story 격리 해제 유스케이스 fake.
 *
 * [failure]를 걸면 그 실패를 던진다.
 */
class RecordingReleaseStoryQuarantineUseCase : ReleaseStoryQuarantineUseCase {
    var invokeCount = 0
    var lastCommand: ReleaseStoryQuarantineCommand? = null
    var failure: AiException? = null

    override suspend fun release(command: ReleaseStoryQuarantineCommand): ReleaseStoryQuarantineResult {
        invokeCount += 1
        lastCommand = command
        failure?.let { throw it }

        val released = AiTestFixture.storyQuarantine(
            storyId = command.storyId,
            consecutiveFailures = AiTestFixture.DEFAULT_QUARANTINE_FAILURE_THRESHOLD
        ).release(AiTestFixture.NOW)

        return ReleaseStoryQuarantineResult(
            quarantine = StoryQuarantineSnapshot.from(released),
            releasedAt = AiTestFixture.NOW
        )
    }
}
