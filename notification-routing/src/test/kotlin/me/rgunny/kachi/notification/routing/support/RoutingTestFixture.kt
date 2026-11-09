package me.rgunny.kachi.notification.routing.support

import me.rgunny.kachi.ai.contract.AiFailureReason
import me.rgunny.kachi.ai.contract.AiKeywordQuarantinedEvent
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.ai.contract.AiSummarySentiment
import me.rgunny.kachi.ai.contract.AiTargetType
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
        keywordQuarantinedTopic: String = "ai.keyword.quarantined",
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
            topics = NotificationRoutingProperties.Topics(summaryCreatedTopic, keywordQuarantinedTopic),
            dlt = NotificationRoutingProperties.Dlt(dltTopic),
            retry = NotificationRoutingProperties.Retry(maxAttempts, backoff),
            userService = NotificationRoutingProperties.UserService(baseUrl, recipientsSearchPath, pageSize, usersPath, timeout, maxInMemorySize),
        )
    }

    fun summaryCreatedEvent(
        schemaVersion: Int = AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION,
        summaryId: String = "summary-1",
        keyword: String = "tesla",
        title: String = "Tesla opens new plant",
        content: String = "Tesla announced a new plant.",
        sourceNewsCount: Int = 3,
    ): AiSummaryCreatedEvent {
        return AiSummaryCreatedEvent(
            schemaVersion = schemaVersion,
            summaryId = summaryId,
            keyword = keyword,
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

    fun keywordQuarantinedEvent(
        schemaVersion: Int = AiKeywordQuarantinedEvent.CURRENT_SCHEMA_VERSION,
        quarantineId: String = "quarantine-1",
        keyword: String = "tesla",
    ): AiKeywordQuarantinedEvent {
        return AiKeywordQuarantinedEvent(
            schemaVersion = schemaVersion,
            quarantineId = quarantineId,
            targetType = AiTargetType.NEWS_SUMMARY,
            keyword = keyword,
            consecutiveFailures = 3,
            lastFailureReason = AiFailureReason.INVALID_RESPONSE,
            quarantinedAt = NOW,
        )
    }

    const val SUMMARY_CREATED_JSON = """
        {
          "schemaVersion": 1,
          "summaryId": "summary-1",
          "keyword": "tesla",
          "title": "Tesla opens new plant",
          "content": "Tesla announced a new plant.",
          "sentiment": "POSITIVE",
          "sourceNewsCount": 3,
          "provider": "groq",
          "model": "llama",
          "promptVersion": "v1",
          "createdAt": "2026-06-13T00:00:00Z"
        }
    """

    const val KEYWORD_QUARANTINED_JSON = """
        {
          "schemaVersion": 1,
          "quarantineId": "quarantine-1",
          "targetType": "NEWS_SUMMARY",
          "keyword": "tesla",
          "consecutiveFailures": 3,
          "lastFailureReason": "INVALID_RESPONSE",
          "quarantinedAt": "2026-06-13T00:00:00Z"
        }
    """
}
