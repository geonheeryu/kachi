package me.rgunny.kachi.notification.routing.adapter.inbound.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.RouteSummaryNotificationUseCase
import me.rgunny.kachi.notification.routing.application.service.RoutingEventKey
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.adapter.monitoring.NotificationRoutingMetrics
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * ai.summary.created Kafka 인입 adapter.
 *
 * schemaVersion 2 story 요약 계약을 읽어 routing command로 바꾸고, 라우팅은 use case에 맡긴다.
 * 읽을 수 없거나 command로 옮길 수 없는 payload는 InvalidAiEventMessageException으로 던진다.
 */
@Component
class AiSummaryCreatedKafkaListener(
    private val routeSummaryNotificationUseCase: RouteSummaryNotificationUseCase,
    private val jsonMapper: JsonMapper,
    private val metrics: NotificationRoutingMetrics,
) {

    @KafkaListener(
        topics = ["\${kachi.notification.routing.topics.summary-created}"],
        groupId = "\${kachi.notification.routing.group-id}",
        containerFactory = "notificationRoutingKafkaListenerContainerFactory",
        properties = ["auto.offset.reset=\${kachi.notification.routing.auto-offset-reset}"],
    )
    fun consume(@Payload payload: String) = runBlocking {
        val startedAt = System.nanoTime()
        val command = try {
            AiSummaryCreatedEventMapper.toCommand(readEvent(payload))
        } catch (exception: IllegalArgumentException) {
            metrics.recordInvalidRoutingMessage(RoutingJobKind.SUMMARY, elapsed(startedAt))
            throw InvalidAiEventMessageException("invalid ai summary created event", exception)
        } catch (exception: InvalidAiEventMessageException) {
            metrics.recordInvalidRoutingMessage(RoutingJobKind.SUMMARY, elapsed(startedAt))
            throw exception
        }

        val result = try {
            routeSummaryNotificationUseCase.routeSummary(command)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            metrics.recordRoutingFailure(RoutingJobKind.SUMMARY, elapsed(startedAt))
            throw exception
        }
        metrics.recordRouting(RoutingJobKind.SUMMARY, result, elapsed(startedAt))

        log.info(
            "ai summary routed eventKey={} outcome={} targets={} published={}",
            RoutingEventKey.forSummary(command.storyId, command.version), result.outcome, result.targetCount, result.publishedCount,
        )
    }

    private fun readEvent(payload: String): AiSummaryCreatedEvent {
        return try {
            jsonMapper.readValue(payload, AiSummaryCreatedEvent::class.java)
        } catch (exception: Exception) {
            throw InvalidAiEventMessageException("invalid ai summary created payload", exception)
        }
    }

    private fun elapsed(startedAt: Long): Duration = Duration.ofNanos(System.nanoTime() - startedAt)

    private companion object {
        val log = LoggerFactory.getLogger(AiSummaryCreatedKafkaListener::class.java)
    }
}
