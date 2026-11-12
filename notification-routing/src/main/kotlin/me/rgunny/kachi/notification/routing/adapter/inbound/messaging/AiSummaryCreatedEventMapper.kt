package me.rgunny.kachi.notification.routing.adapter.inbound.messaging

import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.ai.contract.AiSummarySentiment
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.SummarySentiment

/**
 * story 요약 생성 계약을 routing command로 옮기는 Mapper.
 *
 * schemaVersion이 [AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION]이 아니거나 command 규칙을 어기면 IllegalArgumentException을 던진다.
 */
object AiSummaryCreatedEventMapper {

    fun toCommand(event: AiSummaryCreatedEvent): RouteSummaryCommand {
        require(event.schemaVersion == AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION) {
            "unsupported ai summary created schemaVersion=${event.schemaVersion}"
        }

        return RouteSummaryCommand(
            summaryId = event.summaryId,
            storyId = event.storyId,
            version = event.version,
            keywords = event.keywords,
            title = event.title,
            content = event.content,
            sentiment = toSentiment(event.sentiment),
            sourceNewsCount = event.sourceNewsCount,
            createdAt = event.createdAt,
        )
    }

    private fun toSentiment(sentiment: AiSummarySentiment): SummarySentiment {
        return when (sentiment) {
            AiSummarySentiment.POSITIVE -> SummarySentiment.POSITIVE
            AiSummarySentiment.NEUTRAL -> SummarySentiment.NEUTRAL
            AiSummarySentiment.NEGATIVE -> SummarySentiment.NEGATIVE
            AiSummarySentiment.UNKNOWN -> SummarySentiment.UNKNOWN
        }
    }
}
