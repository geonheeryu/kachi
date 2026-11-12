package me.rgunny.kachi.notification.routing.support

import me.rgunny.kachi.ai.contract.AiDevelopmentKind
import me.rgunny.kachi.ai.contract.AiFailureReason
import me.rgunny.kachi.ai.contract.AiStoryQuarantinedEvent
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.ai.contract.AiSummarySentiment
import me.rgunny.kachi.notification.routing.config.NotificationRoutingProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * routing 어댑터 테스트가 공유하는 고정값.
 */
object RoutingTestFixture {
    val NOW: Instant = Instant.parse("2026-06-13T00:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    const val RECIPIENTS_SEARCH_PATH = "/api/v1/internal/subscriptions/recipients/search"
    const val USERS_PATH = "/api/v1/internal/users"

    fun properties(
        groupId: String = "notification-routing",
        requestTopic: String = "notification.requested",
        autoOffsetReset: String = "earliest",
        summaryCreatedTopic: String = "ai.summary.created",
        storyQuarantinedTopic: String = "ai.story.quarantined",
        dltTopic: String = "notification.routing.dlt",
        maxAttempts: Long = 3,
        backoff: Duration = Duration.ofSeconds(1),
        baseUrl: String = "http://localhost:8080",
        recipientsSearchPath: String = RECIPIENTS_SEARCH_PATH,
        pageSize: Int = 100,
        usersPath: String = USERS_PATH,
        timeout: Duration = Duration.ofSeconds(3),
        maxInMemorySize: Int = 262144,
    ): NotificationRoutingProperties {
        return NotificationRoutingProperties(
            groupId = groupId,
            requestTopic = requestTopic,
            autoOffsetReset = autoOffsetReset,
            topics = NotificationRoutingProperties.Topics(summaryCreatedTopic, storyQuarantinedTopic),
            dlt = NotificationRoutingProperties.Dlt(dltTopic),
            retry = NotificationRoutingProperties.Retry(maxAttempts, backoff),
            userService = NotificationRoutingProperties.UserService(baseUrl, recipientsSearchPath, pageSize, usersPath, timeout, maxInMemorySize),
        )
    }

    fun summaryCreatedEvent(
        schemaVersion: Int = AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION,
        summaryId: String = "summary-1",
        storyId: String = "story-1",
        version: Long = 1,
        keywords: List<String> = listOf("nvidia", "tsmc"),
        title: String = "NVIDIA and TSMC expand partnership",
        content: String = "NVIDIA and TSMC announced a new plant.",
        sourceNewsCount: Int = 3,
    ): AiSummaryCreatedEvent {
        return AiSummaryCreatedEvent(
            schemaVersion = schemaVersion,
            summaryId = summaryId,
            storyId = storyId,
            version = version,
            keywords = keywords,
            developmentKind = AiDevelopmentKind.DEVELOPMENT,
            title = title,
            content = content,
            sentiment = AiSummarySentiment.POSITIVE,
            sourceNewsCount = sourceNewsCount,
            provider = "groq",
            model = "llama",
            promptVersion = "v1",
            createdAt = NOW,
        )
    }

    fun storyQuarantinedEvent(
        schemaVersion: Int = AiStoryQuarantinedEvent.CURRENT_SCHEMA_VERSION,
        quarantineId: String = "quarantine-1",
        storyId: String = "story-1",
    ): AiStoryQuarantinedEvent {
        return AiStoryQuarantinedEvent(
            schemaVersion = schemaVersion,
            quarantineId = quarantineId,
            storyId = storyId,
            consecutiveFailures = 3,
            lastFailureReason = AiFailureReason.INVALID_RESPONSE,
            quarantinedAt = NOW,
        )
    }

    const val SUMMARY_CREATED_JSON = """
        {
          "schemaVersion": 2,
          "summaryId": "summary-1",
          "storyId": "story-1",
          "version": 1,
          "keywords": ["nvidia", "tsmc"],
          "developmentKind": "DEVELOPMENT",
          "title": "NVIDIA and TSMC expand partnership",
          "content": "NVIDIA and TSMC announced a new plant.",
          "sentiment": "POSITIVE",
          "sourceNewsCount": 3,
          "provider": "groq",
          "model": "llama",
          "promptVersion": "v1",
          "createdAt": "2026-06-13T00:00:00Z"
        }
    """

    const val STORY_QUARANTINED_JSON = """
        {
          "schemaVersion": 1,
          "quarantineId": "quarantine-1",
          "storyId": "story-1",
          "consecutiveFailures": 3,
          "lastFailureReason": "INVALID_RESPONSE",
          "quarantinedAt": "2026-06-13T00:00:00Z"
        }
    """
}
