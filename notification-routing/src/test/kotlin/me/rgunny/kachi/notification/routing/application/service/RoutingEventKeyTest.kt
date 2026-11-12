package me.rgunny.kachi.notification.routing.application.service

import java.time.Instant
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("RoutingEventKey")
class RoutingEventKeyTest {

    @Test
    @DisplayName("요약 eventKey는 {storyId}:{version}이다")
    fun forSummary() {
        assertEquals("story-1:3", RoutingEventKey.forSummary("story-1", 3))
    }

    @Test
    @DisplayName("격리 eventKey는 {quarantineId}:{quarantinedAt epoch millis}다")
    fun forQuarantine() {
        assertEquals(
            "quarantine-1:1700000000000",
            RoutingEventKey.forQuarantine("quarantine-1", Instant.ofEpochMilli(1_700_000_000_000)),
        )
    }
}
