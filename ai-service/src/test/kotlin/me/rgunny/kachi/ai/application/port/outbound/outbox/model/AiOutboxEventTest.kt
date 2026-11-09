package me.rgunny.kachi.ai.application.port.outbound.outbox.model

import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantine
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineId
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.summary.NewsSummarySentiment
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("AiOutboxEvent")
class AiOutboxEventTest {
    private val now = AiTestFixture.NOW

    @Nested
    @DisplayName("StorySummaryCreated")
    inner class StorySummaryCreated {

        @Test
        @DisplayName("요약 버전에서 발행할 값을 그대로 옮긴다")
        fun mapFromStorySummary() {
            val summary = AiTestFixture.storySummary(version = 2, keywords = listOf("NVIDIA", "GPU"))

            val event = StorySummaryCreatedEvent.from(summary)

            assertEquals(summary.id.value, event.summaryId)
            assertEquals(AiTestFixture.STORY_ID.value, event.storyId)
            assertEquals(2, event.version)
            assertEquals(listOf("NVIDIA", "GPU"), event.keywords)
            assertEquals(StoryDevelopmentKind.DEVELOPMENT, event.developmentKind)
            assertEquals(summary.title, event.title)
            assertEquals(summary.content, event.content)
            assertEquals(NewsSummarySentiment.NEUTRAL, event.sentiment)
            assertEquals(1, event.sourceNewsCount)
            assertEquals(AiTestFixture.PROVIDER.code, event.provider)
            assertEquals(AiTestFixture.MODEL, event.model)
            assertEquals(AiTestFixture.STORY_SUMMARY_PROMPT_VERSION.value, event.promptVersion)
            assertEquals(now, event.createdAt)
            assertEquals(StorySummaryCreatedEvent.SCHEMA_VERSION, event.schemaVersion)
        }

        @Test
        @DisplayName("storyId와 버전이 이벤트 키이고 storyId가 파티션 키다")
        fun keysComeFromStoryAndVersion() {
            val summary = AiTestFixture.storySummary(version = 2)

            val event = StorySummaryCreatedEvent.from(summary)

            assertEquals(AiOutboxEventType.SUMMARY_CREATED, event.type)
            assertEquals("${AiTestFixture.STORY_ID.value}:2", event.eventKey)
            assertEquals(AiTestFixture.STORY_ID.value.toString(), event.partitionKey)
        }
    }

    @Nested
    @DisplayName("StoryQuarantined")
    inner class StoryQuarantined {

        @Test
        @DisplayName("격리 기록에서 발행할 값을 그대로 옮긴다")
        fun mapFromQuarantine() {
            val quarantine = AiTestFixture.storyQuarantine(consecutiveFailures = 3)

            val event = StoryQuarantinedEvent.from(quarantine)

            assertEquals(quarantine.id.value, event.quarantineId)
            assertEquals(AiTestFixture.STORY_ID.value, event.storyId)
            assertEquals(3, event.consecutiveFailures)
            assertEquals(AiFailureReason.UNKNOWN, event.lastFailureReason)
            assertEquals(now, event.quarantinedAt)
            assertEquals(AiOutboxEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion)
        }

        @Test
        @DisplayName("이벤트 키는 기록 id와 격리 시각을 함께 쓰고 storyId가 파티션 키다")
        fun keyIncludesQuarantinedAt() {
            val quarantine = AiTestFixture.storyQuarantine(consecutiveFailures = 3)

            val event = StoryQuarantinedEvent.from(quarantine)

            assertEquals(AiOutboxEventType.STORY_QUARANTINED, event.type)
            assertEquals("${quarantine.id.value}:${now.toEpochMilli()}", event.eventKey)
            assertEquals(AiTestFixture.STORY_ID.value.toString(), event.partitionKey)
        }

        @Test
        @DisplayName("격리 상태가 아닌 기록으로는 만들 수 없다")
        fun rejectNotQuarantined() {
            val tracking = AiTestFixture.storyQuarantine(consecutiveFailures = 1)

            assertFailsWith<IllegalArgumentException> { StoryQuarantinedEvent.from(tracking) }
        }

        @Test
        @DisplayName("격리 시각이나 마지막 실패 원인이 없으면 만들 수 없다")
        fun rejectMissingQuarantineDetails() {
            assertFailsWith<IllegalArgumentException> {
                StoryQuarantinedEvent.from(
                    restoreQuarantined(quarantinedAt = null, lastFailureReason = AiFailureReason.UNKNOWN)
                )
            }
            assertFailsWith<IllegalArgumentException> {
                StoryQuarantinedEvent.from(
                    restoreQuarantined(quarantinedAt = now, lastFailureReason = null)
                )
            }
        }
    }

    /**
     * 도메인 전이로는 만들 수 없는 결손 기록.
     */
    private fun restoreQuarantined(
        quarantinedAt: Instant?,
        lastFailureReason: AiFailureReason?
    ): StoryQuarantine {
        return StoryQuarantine.restore(
            id = StoryQuarantineId.newId(),
            storyId = AiTestFixture.STORY_ID,
            consecutiveFailures = 3,
            lastFailureReason = lastFailureReason,
            status = StoryQuarantineStatus.QUARANTINED,
            quarantinedAt = quarantinedAt,
            releasedAt = null,
            updatedAt = now
        )
    }
}
