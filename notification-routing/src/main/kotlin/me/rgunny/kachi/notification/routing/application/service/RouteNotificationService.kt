package me.rgunny.kachi.notification.routing.application.service

import java.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rgunny.kachi.notification.contract.NotificationChannel
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
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedRecipient
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobConflictException

/**
 * 이벤트 1건을 수신자 x 채널마다 알림 요청으로 fan-out해 발행한다.
 *
 * 요약은 구독 수신자를 job의 cursor부터 페이지로 읽고, 페이지의 요청을 동시에 발행해 모두 끝난 뒤에 진행을 저장한다.
 * nextCursor가 있으면 advance, 없으면 complete를 조건부로 저장하며, 마지막 페이지는 complete 한 번이다.
 * 격리는 관리자 x 채널 전체를 한 페이지로 발행하고 complete한다.
 * 예외를 잡지 않는다. 발행 실패와 조건부 저장 실패는 그대로 전파되고 job은 마지막으로 저장된 cursor에 남는다.
 * 같은 이벤트가 다시 오면 그 cursor부터 잇고, 다시 발행된 요청은 접수 쪽 requestId 멱등이 거른다.
 */
class RouteNotificationService(
    private val routingJobPersistencePort: RoutingJobPersistencePort,
    private val recipientReaderPort: RecipientReaderPort,
    private val notificationRequestPublisherPort: NotificationRequestPublisherPort,
    private val policy: RoutingPolicy,
    private val clock: Clock,
) : RouteSummaryNotificationUseCase, RouteQuarantineNotificationUseCase {

    override suspend fun routeSummary(command: RouteSummaryCommand): RouteNotificationResult {
        // 1. eventKey당 job 하나. COMPLETED면 다시 라우팅하지 않는다.
        val eventKey = RoutingEventKey.forSummary(command.storyId, command.version)
        val started = findOrStart(eventKey, RoutingJobKind.SUMMARY, command.storyId)
        if (started.status == RoutingJobStatus.COMPLETED) {
            return result(started, RouteNotificationOutcome.SKIPPED)
        }

        // 2. 저장된 cursor부터 페이지마다 발행하고 진행을 저장한다.
        val keywords = command.keywords.toSet()
        var job = started
        while (true) {
            val page = recipientReaderPort.findSubscribers(keywords, job.cursor)
            val requests = page.recipients.map { recipient -> summaryRequest(command, recipient) }
            publishAll(requests)

            val nextCursor = page.nextCursor
                ?: return result(complete(job, requests.size), RouteNotificationOutcome.ROUTED)
            check(nextCursor != job.cursor) {
                "recipient page cursor did not advance. eventKey=$eventKey, cursor=$nextCursor"
            }
            job = routingJobPersistencePort.saveTransition(
                previous = job,
                next = job.advance(
                    cursor = nextCursor,
                    targetDelta = requests.size,
                    publishedDelta = requests.size,
                    now = clock.instant(),
                ),
            )
        }
    }

    override suspend fun routeQuarantine(command: RouteQuarantineCommand): RouteNotificationResult {
        // 1. eventKey당 job 하나. COMPLETED면 다시 라우팅하지 않는다.
        val job = findOrStart(command.eventKey, RoutingJobKind.QUARANTINE, command.storyId)
        if (job.status == RoutingJobStatus.COMPLETED) {
            return result(job, RouteNotificationOutcome.SKIPPED)
        }

        // 2. 관리자 x 채널 전체를 한 페이지로 발행하고 완료를 저장한다.
        val message = NotificationMessageRenderer.renderQuarantine(command)
        val requests = recipientReaderPort.findAdmins().map { recipient -> quarantineRequest(command, recipient, message) }
        publishAll(requests)

        return result(complete(job, requests.size), RouteNotificationOutcome.ROUTED)
    }

    private suspend fun findOrStart(eventKey: String, kind: RoutingJobKind, storyId: String): RoutingJob {
        routingJobPersistencePort.findByEventKey(eventKey)?.let { return it }

        return try {
            routingJobPersistencePort.insert(RoutingJob.start(eventKey, kind, storyId, clock.instant()))
        } catch (exception: RoutingJobConflictException) {
            // 같은 이벤트를 동시에 받은 다른 인스턴스가 먼저 만들었다. 그 job으로 진행한다.
            routingJobPersistencePort.findByEventKey(eventKey)
                ?: throw IllegalStateException("routing job conflict but not found. eventKey=$eventKey", exception)
        }
    }

    /**
     * 한 페이지의 요청을 동시에 발행하고 모두 끝나기를 기다린다.
     *
     * 하나라도 실패하면 나머지를 취소하고 그 예외를 던진다.
     */
    private suspend fun publishAll(requests: List<NotificationRequest>) {
        coroutineScope {
            requests.map { request -> async { notificationRequestPublisherPort.publish(request) } }.awaitAll()
        }
    }

    private suspend fun complete(job: RoutingJob, published: Int): RoutingJob {
        return routingJobPersistencePort.saveTransition(
            previous = job,
            next = job.complete(
                targetDelta = published,
                publishedDelta = published,
                now = clock.instant(),
            ),
        )
    }

    private fun summaryRequest(command: RouteSummaryCommand, recipient: SubscribedRecipient): NotificationRequest {
        return request(
            requestId = RoutingRequestId.forSummary(command.storyId, command.version, recipient.recipientId, recipient.channel),
            recipientId = recipient.recipientId,
            channel = recipient.channel,
            message = NotificationMessageRenderer.renderSummary(command, recipient.keywords),
            origin = NotificationRequestOrigin(
                summaryId = command.summaryId,
                storyId = command.storyId,
                version = command.version,
                keywords = recipient.keywords.map { it.canonicalKey },
                userId = recipient.recipientId,
            ),
        )
    }

    private fun quarantineRequest(command: RouteQuarantineCommand, recipient: Recipient, message: String): NotificationRequest {
        return request(
            requestId = RoutingRequestId.forQuarantine(command.eventKey, recipient.recipientId, recipient.channel),
            recipientId = recipient.recipientId,
            channel = recipient.channel,
            message = message,
            origin = NotificationRequestOrigin(
                summaryId = null,
                storyId = command.storyId,
                version = null,
                keywords = emptyList(),
                userId = recipient.recipientId,
            ),
        )
    }

    private fun request(
        requestId: String,
        recipientId: String,
        channel: NotificationChannel,
        message: String,
        origin: NotificationRequestOrigin,
    ): NotificationRequest {
        return NotificationRequest(
            requestId = requestId,
            requester = policy.requester,
            channel = channel,
            recipientId = recipientId,
            message = message,
            origin = origin,
        )
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
