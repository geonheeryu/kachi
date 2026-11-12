package me.rgunny.kachi.notification.routing.adapter.inbound.messaging

import me.rgunny.kachi.ai.contract.AiStoryQuarantinedEvent
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.application.service.RoutingEventKey

/**
 * story 격리 계약을 routing command로 옮기는 Mapper.
 *
 * eventKey는 quarantineId와 격리 시각 epoch millis를 합친 값이다.
 * schemaVersion이 [AiStoryQuarantinedEvent.CURRENT_SCHEMA_VERSION]이 아니거나 command 규칙을 어기면 IllegalArgumentException을 던진다.
 */
object AiStoryQuarantinedEventMapper {

    fun toCommand(event: AiStoryQuarantinedEvent): RouteQuarantineCommand {
        require(event.schemaVersion == AiStoryQuarantinedEvent.CURRENT_SCHEMA_VERSION) {
            "unsupported ai story quarantined schemaVersion=${event.schemaVersion}"
        }

        return RouteQuarantineCommand(
            eventKey = RoutingEventKey.forQuarantine(event.quarantineId, event.quarantinedAt),
            storyId = event.storyId,
            consecutiveFailures = event.consecutiveFailures,
            lastFailureReason = event.lastFailureReason.name,
            quarantinedAt = event.quarantinedAt,
        )
    }
}
