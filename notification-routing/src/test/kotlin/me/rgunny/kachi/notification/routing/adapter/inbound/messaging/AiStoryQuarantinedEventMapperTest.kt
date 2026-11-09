package me.rgunny.kachi.notification.routing.adapter.inbound.messaging

import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("AiStoryQuarantinedEventMapper")
class AiStoryQuarantinedEventMapperTest {

    @Test
    @DisplayName("eventKey는 quarantineId와 격리 시각 epoch millis를 합친 값이고 storyId와 실패 정보를 옮긴다")
    fun toCommand() {
        val command = AiStoryQuarantinedEventMapper.toCommand(RoutingTestFixture.storyQuarantinedEvent())

        assertEquals(
            RouteQuarantineCommand(
                eventKey = "quarantine-1:${NOW.toEpochMilli()}",
                storyId = "story-1",
                consecutiveFailures = 3,
                lastFailureReason = "INVALID_RESPONSE",
                quarantinedAt = NOW,
            ),
            command,
        )
    }

    @Test
    @DisplayName("schemaVersion이 다르면 거부한다")
    fun rejectSchemaVersion() {
        assertFailsWith<IllegalArgumentException> {
            AiStoryQuarantinedEventMapper.toCommand(RoutingTestFixture.storyQuarantinedEvent(schemaVersion = 2))
        }
    }

    @Test
    @DisplayName("storyId가 비면 거부한다")
    fun rejectBlankStoryId() {
        assertFailsWith<IllegalArgumentException> {
            AiStoryQuarantinedEventMapper.toCommand(RoutingTestFixture.storyQuarantinedEvent(storyId = " "))
        }
    }
}
