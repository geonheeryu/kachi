package me.rgunny.kachi.notification.routing.application.service

import java.time.Clock
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.RouteQuarantineNotificationUseCase
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.RouteSummaryNotificationUseCase
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteNotificationOutcome
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteNotificationResult
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.outbound.job.RoutingJobPersistencePort
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.NotificationRequestPublisherPort
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model.NotificationRequest
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model.NotificationRequestOrigin
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.RecipientReaderPort
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobConflictException

/**
 * 이벤트 1건을 대상마다 알림 요청으로 fan-out해 발행한다.
 *
 * 예외를 잡지 않는다. 대상 중 하나라도 발행에 실패하면 그대로 전파되어 job은 STARTED로 남고,
 * 같은 이벤트가 다시 오면 처음부터 다시 발행한다. 이미 발행된 대상은 접수 쪽 requestId 멱등이 거른다.
 */
class RouteNotificationService(
    private val routingJobPersistencePort: RoutingJobPersistencePort,
    private val recipientReaderPort: RecipientReaderPort,
    private val notificationRequestPublisherPort: NotificationRequestPublisherPort,
    private val policy: RoutingPolicy,
    private val clock: Clock,
) : RouteSummaryNotificationUseCase, RouteQuarantineNotificationUseCase {

    override suspend fun routeSummary(command: RouteSummaryCommand): RouteNotificationResult {
        return route(command.summaryId, RoutingJobKind.SUMMARY, command.keyword) { job ->
            findAllSubscribers(command.keyword).map { recipient ->
                request(
                    requestId = RoutingRequestId.forSummary(command.summaryId, recipient.recipientId, recipient.channel),
                    recipient = recipient,
                    message = command.message,
                    origin = NotificationRequestOrigin(
                        summaryId = command.summaryId,
                        keyword = job.keyword,
                        userId = recipient.recipientId,
                    ),
                )
            }
        }
    }

    override suspend fun routeQuarantine(command: RouteQuarantineCommand): RouteNotificationResult {
        return route(command.eventKey, RoutingJobKind.QUARANTINE, command.keyword) { job ->
            recipientReaderPort.findAdmins().map { recipient ->
                request(
                    requestId = RoutingRequestId.forQuarantine(command.eventKey, recipient.recipientId, recipient.channel),
                    recipient = recipient,
                    message = command.message,
                    origin = NotificationRequestOrigin(
                        summaryId = null,
                        keyword = job.keyword,
                        userId = recipient.recipientId,
                    ),
                )
            }
        }
    }

    private suspend fun findAllSubscribers(keyword: String): List<Recipient> {
        val recipients = mutableListOf<Recipient>()
        var cursor: String? = null
        do {
            val page = recipientReaderPort.findSubscribers(setOf(keyword), cursor)
            page.recipients.mapTo(recipients) { Recipient(recipientId = it.recipientId, channel = it.channel) }
            check(page.nextCursor == null || page.nextCursor != cursor) {
                "recipient page cursor did not advance. cursor=$cursor"
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return recipients
    }

    private fun request(
        requestId: String,
        recipient: Recipient,
        message: String,
        origin: NotificationRequestOrigin,
    ): NotificationRequest {
        return NotificationRequest(
            requestId = requestId,
            requester = policy.requester,
            channel = recipient.channel,
            recipientId = recipient.recipientId,
            message = message,
            origin = origin,
        )
    }

    private suspend fun route(
        eventKey: String,
        kind: RoutingJobKind,
        keyword: String,
        targets: suspend (RoutingJob) -> List<NotificationRequest>,
    ): RouteNotificationResult {
        // 1. eventKey당 job 하나. COMPLETED면 다시 라우팅하지 않는다.
        val job = findOrStart(eventKey, kind, keyword)
        if (job.status == RoutingJobStatus.COMPLETED) {
            return result(job, RouteNotificationOutcome.SKIPPED)
        }

        // 2. 대상마다 순차 발행. 실패는 전파한다.
        val requests = targets(job)
        var published = 0
        requests.forEach { request ->
            notificationRequestPublisherPort.publish(request)
            published += 1
        }

        // 3. 완료 기록.
        val completed = routingJobPersistencePort.saveTransition(
            previous = job,
            next = job.complete(
                targetDelta = requests.size,
                publishedDelta = published,
                now = clock.instant(),
            ),
        )
        return result(completed, RouteNotificationOutcome.ROUTED)
    }

    private suspend fun findOrStart(eventKey: String, kind: RoutingJobKind, keyword: String): RoutingJob {
        routingJobPersistencePort.findByEventKey(eventKey)?.let { return it }

        return try {
            routingJobPersistencePort.insert(RoutingJob.start(eventKey, kind, keyword, clock.instant()))
        } catch (exception: RoutingJobConflictException) {
            // 같은 이벤트를 동시에 받은 다른 인스턴스가 먼저 만들었다. 그 job으로 진행한다.
            routingJobPersistencePort.findByEventKey(eventKey)
                ?: throw IllegalStateException("routing job conflict but not found. eventKey=$eventKey", exception)
        }
    }

    private fun result(job: RoutingJob, outcome: RouteNotificationOutcome): RouteNotificationResult {
        return RouteNotificationResult(
            jobId = job.id,
            outcome = outcome,
            targetCount = job.targetCount,
            publishedCount = job.publishedCount,
        )
    }
}
