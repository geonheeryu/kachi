package me.rgunny.kachi.notification.routing.application.service

import me.rgunny.kachi.notification.contract.NotificationChannel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@DisplayName("RoutingRequestId")
class RoutingRequestIdTest {

    @Test
    @DisplayName("요약 requestId는 sum:{storyId}:{version}:u:{recipientId}:c:{CHANNEL}이다")
    fun forSummary() {
        assertEquals(
            "sum:story-1:3:u:user-1:c:SLACK",
            RoutingRequestId.forSummary("story-1", 3, "user-1", NotificationChannel.SLACK),
        )
    }

    @Test
    @DisplayName("같은 story의 다른 version은 다른 requestId다")
    fun differentVersion() {
        assertNotEquals(
            RoutingRequestId.forSummary("story-1", 1, "user-1", NotificationChannel.SLACK),
            RoutingRequestId.forSummary("story-1", 2, "user-1", NotificationChannel.SLACK),
        )
    }

    @Test
    @DisplayName("격리 requestId는 qrt:{eventKey}:u:{recipientId}:c:{CHANNEL}이다")
    fun forQuarantine() {
        assertEquals(
            "qrt:quarantine-1:1700000000000:u:user-9:c:TELEGRAM",
            RoutingRequestId.forQuarantine("quarantine-1:1700000000000", "user-9", NotificationChannel.TELEGRAM),
        )
    }

    @Test
    @DisplayName("같은 입력은 같은 requestId를 만든다")
    fun deterministic() {
        val first = RoutingRequestId.forSummary("story-1", 1, "user-1", NotificationChannel.DISCORD)
        val second = RoutingRequestId.forSummary("story-1", 1, "user-1", NotificationChannel.DISCORD)

        assertEquals(first, second)
    }
}
