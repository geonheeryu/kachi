package me.rgunny.kachi.notification.fixture

import me.rgunny.kachi.notification.domain.NotificationOrigin
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

object NotificationTestFixture {
    val NOW: Instant = Instant.parse("2026-06-13T00:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val DEDUPE_TTL: Duration = Duration.ofMinutes(5)
    val IDEMPOTENCY_KEY_TTL: Duration = Duration.ofHours(1)

    const val REQUEST_ID = "request-1"
    const val REQUESTER = "api"
    const val RECIPIENT_ID = "user-1"
    const val MESSAGE = "hello"
    const val DISPATCH_TOPIC = "notification.dispatch"
    val ORIGIN = NotificationOrigin(
        summaryId = "summary-1",
        storyId = "story-1",
        version = 1,
        keywords = listOf("tesla"),
        userId = "user-1",
    )
}
