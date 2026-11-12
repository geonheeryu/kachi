package me.rgunny.kachi.ai.adapter.outbound.outbox

import me.rgunny.kachi.ai.application.port.outbound.outbox.AiOutboxEventSerializer
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.AiOutboxEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StoryQuarantinedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySplitRequestedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySummaryCreatedEvent
import me.rgunny.kachi.ai.contract.AiDevelopmentKind
import me.rgunny.kachi.ai.contract.AiFailureReason
import me.rgunny.kachi.ai.contract.AiStoryQuarantinedEvent
import me.rgunny.kachi.ai.contract.AiStorySplitRequestedEvent
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.ai.contract.AiSummarySentiment
import me.rgunny.kachi.ai.domain.summary.NewsSummarySentiment
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import me.rgunny.kachi.ai.domain.run.AiFailureReason as DomainFailureReason

/**
 * application 이벤트를 계약 객체로 옮긴 뒤 JSON으로 쓰는 serializer.
 */
@Component
class JacksonAiOutboxEventSerializer(
    private val jsonMapper: JsonMapper
) : AiOutboxEventSerializer {

    override fun serialize(event: AiOutboxEvent): String {
        val contract: Any = when (event) {
            is StorySummaryCreatedEvent -> toContract(event)
            is StorySplitRequestedEvent -> toContract(event)
            is StoryQuarantinedEvent -> toContract(event)
        }

        return jsonMapper.writeValueAsString(contract)
    }

    private fun toContract(event: StorySummaryCreatedEvent): AiSummaryCreatedEvent {
        return AiSummaryCreatedEvent(
            schemaVersion = event.schemaVersion,
            summaryId = event.summaryId.toString(),
            storyId = event.storyId.toString(),
            version = event.version,
            keywords = event.keywords,
            developmentKind = toContract(event.developmentKind),
            title = event.title,
            content = event.content,
            sentiment = toContract(event.sentiment),
            sourceNewsCount = event.sourceNewsCount,
            provider = event.provider,
            model = event.model,
            promptVersion = event.promptVersion,
            createdAt = event.createdAt
        )
    }

    private fun toContract(event: StorySplitRequestedEvent): AiStorySplitRequestedEvent {
        return AiStorySplitRequestedEvent(
            schemaVersion = event.schemaVersion,
            storyId = event.storyId.toString(),
            summaryId = event.summaryId.toString(),
            newsIds = event.newsIds.map { it.toString() },
            requestedAt = event.requestedAt
        )
    }

    private fun toContract(event: StoryQuarantinedEvent): AiStoryQuarantinedEvent {
        return AiStoryQuarantinedEvent(
            schemaVersion = event.schemaVersion,
            quarantineId = event.quarantineId.toString(),
            storyId = event.storyId.toString(),
            consecutiveFailures = event.consecutiveFailures,
            lastFailureReason = toContract(event.lastFailureReason),
            quarantinedAt = event.quarantinedAt
        )
    }

    private fun toContract(kind: StoryDevelopmentKind): AiDevelopmentKind {
        return when (kind) {
            StoryDevelopmentKind.NEW_STORY -> AiDevelopmentKind.NEW_STORY
            StoryDevelopmentKind.DEVELOPMENT -> AiDevelopmentKind.DEVELOPMENT
            StoryDevelopmentKind.NO_CHANGE -> AiDevelopmentKind.NO_CHANGE
        }
    }

    private fun toContract(sentiment: NewsSummarySentiment): AiSummarySentiment {
        return when (sentiment) {
            NewsSummarySentiment.POSITIVE -> AiSummarySentiment.POSITIVE
            NewsSummarySentiment.NEUTRAL -> AiSummarySentiment.NEUTRAL
            NewsSummarySentiment.NEGATIVE -> AiSummarySentiment.NEGATIVE
            NewsSummarySentiment.UNKNOWN -> AiSummarySentiment.UNKNOWN
        }
    }

    private fun toContract(reason: DomainFailureReason): AiFailureReason {
        return when (reason) {
            DomainFailureReason.TIMEOUT -> AiFailureReason.TIMEOUT
            DomainFailureReason.RATE_LIMITED -> AiFailureReason.RATE_LIMITED
            DomainFailureReason.CLIENT_ERROR -> AiFailureReason.CLIENT_ERROR
            DomainFailureReason.SERVER_ERROR -> AiFailureReason.SERVER_ERROR
            DomainFailureReason.NETWORK_ERROR -> AiFailureReason.NETWORK_ERROR
            DomainFailureReason.INVALID_RESPONSE -> AiFailureReason.INVALID_RESPONSE
            DomainFailureReason.PROVIDER_UNAVAILABLE -> AiFailureReason.PROVIDER_UNAVAILABLE
            // 4xx 계열(격리 이벤트에 실리지 않는 코드)
            DomainFailureReason.MODEL_NOT_FOUND,
            DomainFailureReason.ACCOUNT_ERROR -> AiFailureReason.CLIENT_ERROR
            DomainFailureReason.UNKNOWN -> AiFailureReason.UNKNOWN
        }
    }
}
