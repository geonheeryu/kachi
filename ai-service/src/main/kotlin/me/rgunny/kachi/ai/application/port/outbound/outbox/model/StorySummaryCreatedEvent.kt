package me.rgunny.kachi.ai.application.port.outbound.outbox.model

import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.domain.summary.NewsSummarySentiment
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.domain.summary.StorySummary
import java.time.Instant
import java.util.UUID

/**
 * story 요약 버전이 새로 만들어진 사건.
 *
 * schemaVersion은 2다.
 */
data class StorySummaryCreatedEvent(
    val summaryId: UUID,
    val storyId: UUID,
    val version: Long,
    val keywords: List<String>,
    val developmentKind: StoryDevelopmentKind,
    val title: String,
    val content: String,
    val sentiment: NewsSummarySentiment,
    val sourceNewsCount: Int,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val createdAt: Instant,
    override val schemaVersion: Int = SCHEMA_VERSION
) : AiOutboxEvent {

    override val type: AiOutboxEventType
        get() = AiOutboxEventType.SUMMARY_CREATED

    override val eventKey: String
        get() = "$storyId:$version"

    override val partitionKey: String
        get() = storyId.toString()

    companion object {
        const val SCHEMA_VERSION = 2

        fun from(summary: StorySummary): StorySummaryCreatedEvent {
            return StorySummaryCreatedEvent(
                summaryId = summary.id.value,
                storyId = summary.storyId.value,
                version = summary.version,
                keywords = summary.keywords.map { it.value },
                developmentKind = summary.developmentKind,
                title = summary.title,
                content = summary.content,
                sentiment = summary.sentiment,
                sourceNewsCount = summary.sourceNewsCount,
                provider = summary.provider.code,
                model = summary.model,
                promptVersion = summary.promptVersion.value,
                createdAt = summary.createdAt
            )
        }
    }
}
