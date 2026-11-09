package me.rgunny.kachi.notification.routing.application.service

import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteNotificationOutcome
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model.NotificationRequestOrigin
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.RecipientPage
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedKeyword
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedRecipient
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import me.rgunny.kachi.notification.routing.exception.routing.RoutingErrorCode
import me.rgunny.kachi.notification.routing.exception.routing.RecipientReaderException
import me.rgunny.kachi.notification.routing.fake.FakeNotificationRequestPublisherPort
import me.rgunny.kachi.notification.routing.fake.FakeRoutingJobPersistencePort
import me.rgunny.kachi.notification.routing.fake.FakeRecipientReaderPort
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.CLOCK
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import me.rgunny.kachi.notification.routing.support.runSuspend
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DisplayName("RouteNotificationService")
class RouteNotificationServiceTest {
    private val now = NOW
    private val clock = CLOCK

    private val tesla = listOf(SubscribedKeyword("tesla", "Tesla"))
    private val subscribers = listOf(
        RecipientPage(
            recipients = listOf(
                SubscribedRecipient("user-1", NotificationChannel.SLACK, tesla),
                SubscribedRecipient("user-1", NotificationChannel.TELEGRAM, tesla),
                SubscribedRecipient("user-2", NotificationChannel.DISCORD, tesla),
            ),
            nextCursor = null,
        ),
    )
    private val admins = listOf(
        Recipient("admin-1", NotificationChannel.SLACK),
        Recipient("admin-1", NotificationChannel.TELEGRAM),
        Recipient("admin-2", NotificationChannel.DISCORD),
    )

    @Test
    @DisplayName("구독자 x 채널마다 결정적 requestId로 발행한다")
    fun routeSummary() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(subscribers)
        val publisher = FakeNotificationRequestPublisherPort()
        val service = service(jobs, reader, publisher)

        val result = service.routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(3, result.targetCount)
        assertEquals(3, result.publishedCount)
        assertEquals(listOf<Pair<Set<String>, String?>>(setOf("tesla") to null), reader.subscriberRequests)
        assertEquals(
            listOf("sum:summary-1:u:user-1:c:SLACK", "sum:summary-1:u:user-1:c:TELEGRAM", "sum:summary-1:u:user-2:c:DISCORD"),
            publisher.published.map { it.requestId },
        )
        val first = publisher.published.first()
        assertEquals("notification-routing", first.requester)
        assertEquals(NotificationChannel.SLACK, first.channel)
        assertEquals("user-1", first.recipientId)
        assertEquals("summary body", first.message)
        assertEquals(NotificationRequestOrigin("summary-1", "tesla", "user-1"), first.origin)

        val job = jobs.saved.single()
        assertEquals(RoutingJobStatus.COMPLETED, job.status)
        assertEquals(RoutingJobKind.SUMMARY, job.kind)
        assertEquals("summary-1", job.eventKey)
        assertEquals(now, job.completedAt)
        assertEquals(job.id, result.jobId)
    }

    @Test
    @DisplayName("nextCursor가 없을 때까지 페이지를 이어 읽고 모은 수신자를 모두 발행한다")
    fun collectSubscriberPages() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(
            listOf(
                RecipientPage(listOf(SubscribedRecipient("user-1", NotificationChannel.SLACK, tesla)), nextCursor = "user-1"),
                RecipientPage(emptyList(), nextCursor = "user-2"),
                RecipientPage(listOf(SubscribedRecipient("user-3", NotificationChannel.DISCORD, tesla)), nextCursor = null),
            )
        )
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(
            listOf<Pair<Set<String>, String?>>(setOf("tesla") to null, setOf("tesla") to "user-1", setOf("tesla") to "user-2"),
            reader.subscriberRequests,
        )
        assertEquals(
            listOf("sum:summary-1:u:user-1:c:SLACK", "sum:summary-1:u:user-3:c:DISCORD"),
            publisher.published.map { it.requestId },
        )
        assertEquals(2, result.targetCount)
        assertEquals(2, result.publishedCount)
        assertEquals(RoutingJobStatus.COMPLETED, jobs.saved.single().status)
    }

    @Test
    @DisplayName("COMPLETED job은 다시 라우팅하지 않는다")
    fun skipCompleted() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort().also {
            it.put(RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now.minusSeconds(60)).complete(3, 3, now.minusSeconds(30)))
        }
        val reader = FakeRecipientReaderPort(subscribers)
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.SKIPPED, result.outcome)
        assertEquals(3, result.targetCount)
        assertTrue(publisher.published.isEmpty())
        assertTrue(reader.subscriberRequests.isEmpty())
        assertTrue(jobs.saved.isEmpty())
    }

    @Test
    @DisplayName("STARTED job은 새로 만들지 않고 전체를 다시 발행한다")
    fun resumeStarted() = runSuspend {
        val started = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now.minusSeconds(60))
        val jobs = FakeRoutingJobPersistencePort().also { it.put(started) }
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, FakeRecipientReaderPort(subscribers), publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(3, result.targetCount)
        assertEquals(3, result.publishedCount)
        assertEquals(3, publisher.published.size)
        assertTrue(jobs.inserted.isEmpty())
        assertEquals(started.id, jobs.saved.single().id)
    }

    @Test
    @DisplayName("구독자가 없으면 발행 없이 완료한다")
    fun noSubscribers() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, FakeRecipientReaderPort(), publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(0, result.targetCount)
        assertTrue(publisher.published.isEmpty())
        assertEquals(RoutingJobStatus.COMPLETED, jobs.saved.single().status)
    }

    @Test
    @DisplayName("발행 중 예외는 전파하고 job은 STARTED로 남는다")
    fun propagatePublishFailure() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val publisher = FakeNotificationRequestPublisherPort().also { it.failAt = 2 }

        assertFailsWith<IllegalStateException> {
            service(jobs, FakeRecipientReaderPort(subscribers), publisher).routeSummary(summaryCommand())
        }

        assertEquals(2, publisher.published.size)
        assertTrue(jobs.saved.isEmpty())
        assertEquals(RoutingJobStatus.STARTED, jobs.findByEventKey("summary-1")!!.status)
    }

    @Test
    @DisplayName("수신자 조회 실패는 전파한다")
    fun propagateReaderFailure() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort().also {
            it.failure = RecipientReaderException(RoutingErrorCode.USER_SERVICE_REQUEST_FAILED, "status=503")
        }
        val publisher = FakeNotificationRequestPublisherPort()

        assertFailsWith<RecipientReaderException> {
            service(jobs, reader, publisher).routeSummary(summaryCommand())
        }

        assertTrue(publisher.published.isEmpty())
        assertEquals(RoutingJobStatus.STARTED, jobs.findByEventKey("summary-1")!!.status)
    }

    @Test
    @DisplayName("insert 충돌이면 재조회한 job으로 진행한다")
    fun resolveInsertConflict() = runSuspend {
        val existing = RoutingJob.start("summary-1", RoutingJobKind.SUMMARY, "tesla", now.minusSeconds(1))
        val jobs = FakeRoutingJobPersistencePort().also { it.conflictOnce = existing }
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, FakeRecipientReaderPort(subscribers), publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(existing.id, result.jobId)
        assertTrue(jobs.inserted.isEmpty())
        assertEquals(3, publisher.published.size)
    }

    @Test
    @DisplayName("격리 알림은 관리자 x 채널마다 구독자 알림과 같은 모양으로 발행한다")
    fun routeQuarantine() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(subscriberPages = subscribers, admins = admins)
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeQuarantine(
            RouteQuarantineCommand(eventKey = "quarantine-1:1700000000000", keyword = "tesla", message = "quarantine body")
        )

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(3, result.targetCount)
        assertEquals(3, result.publishedCount)
        assertTrue(reader.subscriberRequests.isEmpty())
        assertEquals(1, reader.adminRequests)
        assertEquals(
            listOf(
                "qrt:quarantine-1:1700000000000:u:admin-1:c:SLACK",
                "qrt:quarantine-1:1700000000000:u:admin-1:c:TELEGRAM",
                "qrt:quarantine-1:1700000000000:u:admin-2:c:DISCORD",
            ),
            publisher.published.map { it.requestId },
        )
        val first = publisher.published.first()
        assertEquals("notification-routing", first.requester)
        assertEquals(NotificationChannel.SLACK, first.channel)
        assertEquals("admin-1", first.recipientId)
        assertEquals("quarantine body", first.message)
        assertEquals(NotificationRequestOrigin(summaryId = null, keyword = "tesla", userId = "admin-1"), first.origin)
        val job = jobs.saved.single()
        assertEquals(RoutingJobKind.QUARANTINE, job.kind)
        assertEquals("quarantine-1:1700000000000", job.eventKey)
    }

    @Test
    @DisplayName("관리자가 없으면 대상 0으로 완료한다")
    fun routeQuarantineWithoutAdmins() = runSuspend {
        val jobs = FakeRoutingJobPersistencePort()
        val publisher = FakeNotificationRequestPublisherPort()
        val service = service(jobs, FakeRecipientReaderPort(subscriberPages = subscribers), publisher)

        val result = service.routeQuarantine(RouteQuarantineCommand("quarantine-1:1", "tesla", "quarantine body"))

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(0, result.targetCount)
        assertTrue(publisher.published.isEmpty())
        assertEquals(RoutingJobStatus.COMPLETED, jobs.saved.single().status)
    }

    private fun service(
        jobs: FakeRoutingJobPersistencePort,
        reader: FakeRecipientReaderPort,
        publisher: FakeNotificationRequestPublisherPort,
    ): RouteNotificationService {
        return RouteNotificationService(
            routingJobPersistencePort = jobs,
            recipientReaderPort = reader,
            notificationRequestPublisherPort = publisher,
            policy = RoutingPolicy(requester = "notification-routing"),
            clock = clock,
        )
    }

    private fun summaryCommand(): RouteSummaryCommand {
        return RouteSummaryCommand(summaryId = "summary-1", keyword = "tesla", message = "summary body")
    }
}
