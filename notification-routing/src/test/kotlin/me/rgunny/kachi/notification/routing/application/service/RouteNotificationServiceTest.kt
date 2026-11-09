package me.rgunny.kachi.notification.routing.application.service

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteNotificationOutcome
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.SummarySentiment
import me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model.NotificationRequestOrigin
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.RecipientPage
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedKeyword
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedRecipient
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import me.rgunny.kachi.notification.routing.exception.routing.RecipientReaderException
import me.rgunny.kachi.notification.routing.exception.routing.RoutingErrorCode
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobTransitionConflictException
import me.rgunny.kachi.notification.routing.fake.FakeNotificationRequestPublisherPort
import me.rgunny.kachi.notification.routing.fake.FakeRecipientReaderPort
import me.rgunny.kachi.notification.routing.fake.FakeRoutingJobPersistencePort
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.CLOCK
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("RouteNotificationService")
class RouteNotificationServiceTest {
    private val now = NOW
    private val clock = CLOCK

    private val nvidia = SubscribedKeyword("nvidia", "NVIDIA")
    private val tsmc = SubscribedKeyword("tsmc", "TSMC")
    private val storyKeywords = setOf("nvidia", "tsmc")
    private val summaryEventKey = "story-1:1"

    @Test
    @DisplayName("페이지가 둘이면 첫 페이지 뒤 advance, 둘째 페이지 뒤 complete를 차례로 저장하고 누계를 더한다")
    fun twoPages() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(
            listOf(
                page("user-2", recipient("user-1", NotificationChannel.SLACK), recipient("user-2", NotificationChannel.DISCORD)),
                page(null, recipient("user-3", NotificationChannel.TELEGRAM)),
            )
        )
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(listOf<Pair<Set<String>, String?>>(storyKeywords to null, storyKeywords to "user-2"), reader.subscriberRequests)
        assertEquals(3, publisher.published.size)
        assertEquals(listOf(RoutingJobStatus.STARTED, RoutingJobStatus.COMPLETED), jobs.saved.map { it.status })
        assertEquals("user-2", jobs.saved[0].cursor)
        assertEquals(2, jobs.saved[0].targetCount)
        assertEquals(2, jobs.saved[0].publishedCount)
        assertNull(jobs.saved[1].cursor)
        assertEquals(3, jobs.saved[1].targetCount)
        assertEquals(3, jobs.saved[1].publishedCount)
        assertEquals(3, result.targetCount)
        assertEquals(3, result.publishedCount)
    }

    @Test
    @DisplayName("페이지가 하나면 advance 없이 complete 한 번만 저장하고 수신자의 구독 키워드를 출처에 싣는다")
    fun singlePage() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(listOf(page(null, recipient("user-1", NotificationChannel.SLACK, tsmc))))
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        val job = jobs.saved.single()
        assertEquals(RoutingJobStatus.COMPLETED, job.status)
        assertEquals(RoutingJobKind.SUMMARY, job.kind)
        assertEquals(summaryEventKey, job.eventKey)
        assertEquals("story-1", job.storyId)
        assertEquals(now, job.completedAt)
        assertEquals(job.id, result.jobId)
        val request = publisher.published.single()
        assertEquals("notification-routing", request.requester)
        assertEquals(NotificationChannel.SLACK, request.channel)
        assertEquals("user-1", request.recipientId)
        assertEquals(
            NotificationRequestOrigin(summaryId = "summary-1", storyId = "story-1", version = 1, keywords = listOf("tsmc"), userId = "user-1"),
            request.origin,
        )
    }

    @Test
    @DisplayName("둘째 페이지 발행이 실패하면 예외를 전파하고 job은 STARTED로 첫 페이지의 nextCursor에 남는다")
    fun failOnSecondPage() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(
            listOf(
                page("user-1", recipient("user-1", NotificationChannel.SLACK)),
                page(null, recipient("user-2", NotificationChannel.SLACK), recipient("user-3", NotificationChannel.SLACK)),
            )
        )
        val publisher = FakeNotificationRequestPublisherPort().also { it.failAt = 2 }

        assertFailsWith<IllegalStateException> { service(jobs, reader, publisher).routeSummary(summaryCommand()) }

        val stored = jobs.findByEventKey(summaryEventKey)!!
        assertEquals(RoutingJobStatus.STARTED, stored.status)
        assertEquals("user-1", stored.cursor)
        assertEquals(1, stored.targetCount)
        assertEquals(listOf(RoutingJobStatus.STARTED), jobs.saved.map { it.status })
    }

    @Test
    @DisplayName("STARTED이고 cursor가 있는 job은 새로 만들지 않고 그 cursor로 조회를 시작한다")
    fun resumeFromCursor() = runBlocking {
        val started = RoutingJob.start(summaryEventKey, RoutingJobKind.SUMMARY, "story-1", now.minusSeconds(60))
            .advance("user-5", targetDelta = 4, publishedDelta = 4, now = now.minusSeconds(30))
        val jobs = FakeRoutingJobPersistencePort().also { it.put(started) }
        val reader = FakeRecipientReaderPort(listOf(page(null, recipient("user-6", NotificationChannel.SLACK))))
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(listOf<Pair<Set<String>, String?>>(storyKeywords to "user-5"), reader.subscriberRequests)
        assertTrue(jobs.inserted.isEmpty())
        assertEquals(started.id, jobs.saved.single().id)
        assertEquals(5, result.targetCount)
        assertEquals(5, result.publishedCount)
    }

    @Test
    @DisplayName("수신자가 0명이고 nextCursor가 있는 페이지도 advance하고 다음 페이지로 간다")
    fun continueThroughEmptyPage() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(
            listOf(
                page("user-3"),
                page(null, recipient("user-4", NotificationChannel.SLACK)),
            )
        )
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(listOf<Pair<Set<String>, String?>>(storyKeywords to null, storyKeywords to "user-3"), reader.subscriberRequests)
        assertEquals(listOf("user-3", null), jobs.saved.map { it.cursor })
        assertEquals(0, jobs.saved[0].targetCount)
        assertEquals(1, result.targetCount)
        assertEquals(1, publisher.published.size)
    }

    @Test
    @DisplayName("구독자가 없으면 발행 없이 complete 한 번으로 끝낸다")
    fun noSubscribers() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, FakeRecipientReaderPort(), publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(0, result.targetCount)
        assertTrue(publisher.published.isEmpty())
        assertEquals(RoutingJobStatus.COMPLETED, jobs.saved.single().status)
    }

    @Test
    @DisplayName("COMPLETED job은 SKIPPED이고 수신자를 조회하지 않는다")
    fun skipCompleted() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort().also {
            it.put(RoutingJob.start(summaryEventKey, RoutingJobKind.SUMMARY, "story-1", now.minusSeconds(60)).complete(3, 3, now.minusSeconds(30)))
        }
        val reader = FakeRecipientReaderPort()
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.SKIPPED, result.outcome)
        assertEquals(3, result.targetCount)
        assertTrue(reader.subscriberRequests.isEmpty())
        assertTrue(publisher.published.isEmpty())
        assertTrue(jobs.saved.isEmpty())
    }

    @Test
    @DisplayName("같은 story에서 구독 키워드가 다른 수신자는 본문 머리가 다르다")
    fun renderPerRecipient() = runBlocking {
        val reader = FakeRecipientReaderPort(
            listOf(
                page(
                    null,
                    recipient("user-1", NotificationChannel.SLACK, nvidia),
                    recipient("user-2", NotificationChannel.SLACK, nvidia, tsmc),
                )
            )
        )
        val publisher = FakeNotificationRequestPublisherPort()

        service(FakeRoutingJobPersistencePort(), reader, publisher).routeSummary(summaryCommand())

        val messages = publisher.published.associate { it.recipientId to it.message }
        assertTrue(messages.getValue("user-1").startsWith("[NVIDIA] NVIDIA and TSMC expand partnership"))
        assertTrue(messages.getValue("user-2").startsWith("[NVIDIA, TSMC] NVIDIA and TSMC expand partnership"))
        assertEquals(listOf("nvidia"), publisher.published.first { it.recipientId == "user-1" }.origin.keywords)
        assertEquals(listOf("nvidia", "tsmc"), publisher.published.first { it.recipientId == "user-2" }.origin.keywords)
    }

    @Test
    @DisplayName("requestId에 storyId와 version이 들어가고 같은 story의 다른 version은 다른 requestId다")
    fun requestIdHasStoryAndVersion() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val publisher = FakeNotificationRequestPublisherPort()
        val pages = listOf(page(null, recipient("user-1", NotificationChannel.SLACK)))

        service(jobs, FakeRecipientReaderPort(pages), publisher).routeSummary(summaryCommand(version = 1))
        service(jobs, FakeRecipientReaderPort(pages), publisher).routeSummary(summaryCommand(version = 2))

        val requestIds = publisher.published.map { it.requestId }
        assertEquals(listOf("sum:story-1:1:u:user-1:c:SLACK", "sum:story-1:2:u:user-1:c:SLACK"), requestIds)
        assertNotEquals(requestIds[0], requestIds[1])
        assertEquals(listOf("story-1:1", "story-1:2"), jobs.saved.map { it.eventKey })
    }

    @Test
    @DisplayName("조건부 저장이 어긋나면 예외를 전파하고 다시 시도하지 않는다")
    fun propagateTransitionConflict() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(listOf(page("user-1", recipient("user-1", NotificationChannel.SLACK))))
        val publisher = FakeNotificationRequestPublisherPort().also { publisher ->
            // 발행 도중 다른 인스턴스가 같은 job을 완료한 상황
            publisher.onPublish = {
                val stored = jobs.jobsByEventKey.getValue(summaryEventKey)
                jobs.put(stored.complete(1, 1, now))
            }
        }

        assertFailsWith<RoutingJobTransitionConflictException> {
            service(jobs, reader, publisher).routeSummary(summaryCommand())
        }

        assertTrue(jobs.saved.isEmpty())
        assertEquals(1, reader.subscriberRequests.size)
        assertEquals(RoutingJobStatus.COMPLETED, jobs.jobsByEventKey.getValue(summaryEventKey).status)
    }

    @Test
    @DisplayName("nextCursor가 저장된 cursor와 같으면 진행하지 않고 실패한다")
    fun rejectStalledCursor() = runBlocking {
        val started = RoutingJob.start(summaryEventKey, RoutingJobKind.SUMMARY, "story-1", now.minusSeconds(60))
            .advance("user-5", 1, 1, now.minusSeconds(30))
        val jobs = FakeRoutingJobPersistencePort().also { it.put(started) }
        val reader = FakeRecipientReaderPort(listOf(page("user-5", recipient("user-6", NotificationChannel.SLACK))))

        assertFailsWith<IllegalStateException> {
            service(jobs, reader, FakeNotificationRequestPublisherPort()).routeSummary(summaryCommand())
        }

        assertTrue(jobs.saved.isEmpty())
    }

    @Test
    @DisplayName("수신자 조회 실패는 전파하고 job은 STARTED로 남는다")
    fun propagateReaderFailure() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort().also {
            it.failure = RecipientReaderException(RoutingErrorCode.USER_SERVICE_REQUEST_FAILED, "status=503")
        }
        val publisher = FakeNotificationRequestPublisherPort()

        assertFailsWith<RecipientReaderException> {
            service(jobs, reader, publisher).routeSummary(summaryCommand())
        }

        assertTrue(publisher.published.isEmpty())
        assertEquals(RoutingJobStatus.STARTED, jobs.findByEventKey(summaryEventKey)!!.status)
    }

    @Test
    @DisplayName("insert 충돌이면 재조회한 job으로 진행한다")
    fun resolveInsertConflict() = runBlocking {
        val existing = RoutingJob.start(summaryEventKey, RoutingJobKind.SUMMARY, "story-1", now.minusSeconds(1))
        val jobs = FakeRoutingJobPersistencePort().also { it.conflictOnce = existing }
        val publisher = FakeNotificationRequestPublisherPort()
        val reader = FakeRecipientReaderPort(listOf(page(null, recipient("user-1", NotificationChannel.SLACK))))

        val result = service(jobs, reader, publisher).routeSummary(summaryCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(existing.id, result.jobId)
        assertTrue(jobs.inserted.isEmpty())
        assertEquals(1, publisher.published.size)
    }

    @Test
    @DisplayName("격리는 관리자 조회 결과 x 채널 전체를 한 번에 발행하고 complete 한 번을 저장한다")
    fun routeQuarantine() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val reader = FakeRecipientReaderPort(
            admins = listOf(
                Recipient("user-9", NotificationChannel.SLACK),
                Recipient("user-9", NotificationChannel.TELEGRAM),
                Recipient("user-8", NotificationChannel.DISCORD),
            )
        )
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, reader, publisher).routeQuarantine(quarantineCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(3, result.targetCount)
        assertEquals(3, result.publishedCount)
        assertTrue(reader.subscriberRequests.isEmpty())
        assertEquals(1, reader.adminRequests)
        assertEquals(
            listOf(
                "qrt:quarantine-1:1700000000000:u:user-9:c:SLACK",
                "qrt:quarantine-1:1700000000000:u:user-9:c:TELEGRAM",
                "qrt:quarantine-1:1700000000000:u:user-8:c:DISCORD",
            ),
            publisher.published.map { it.requestId },
        )
        val first = publisher.published.first()
        assertEquals("[story 격리] 'story-1' — 연속 실패 3회, 사유 INVALID_RESPONSE, 2026-06-13T00:00:00Z", first.message)
        assertEquals(
            NotificationRequestOrigin(summaryId = null, storyId = "story-1", version = null, keywords = emptyList(), userId = "user-9"),
            first.origin,
        )
        val job = jobs.saved.single()
        assertEquals(RoutingJobStatus.COMPLETED, job.status)
        assertEquals(RoutingJobKind.QUARANTINE, job.kind)
        assertEquals("quarantine-1:1700000000000", job.eventKey)
        assertEquals("story-1", job.storyId)
    }

    @Test
    @DisplayName("관리자가 없으면 대상 0으로 완료한다")
    fun routeQuarantineWithoutAdmins() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort()
        val publisher = FakeNotificationRequestPublisherPort()

        val result = service(jobs, FakeRecipientReaderPort(), publisher).routeQuarantine(quarantineCommand())

        assertEquals(RouteNotificationOutcome.ROUTED, result.outcome)
        assertEquals(0, result.targetCount)
        assertTrue(publisher.published.isEmpty())
        assertEquals(RoutingJobStatus.COMPLETED, jobs.saved.single().status)
    }

    @Test
    @DisplayName("격리 COMPLETED job은 SKIPPED이고 관리자를 조회하지 않는다")
    fun skipCompletedQuarantine() = runBlocking {
        val jobs = FakeRoutingJobPersistencePort().also {
            it.put(RoutingJob.start("quarantine-1:1700000000000", RoutingJobKind.QUARANTINE, "story-1", now).complete(1, 1, now))
        }
        val reader = FakeRecipientReaderPort()

        val result = service(jobs, reader, FakeNotificationRequestPublisherPort()).routeQuarantine(quarantineCommand())

        assertEquals(RouteNotificationOutcome.SKIPPED, result.outcome)
        assertEquals(0, reader.adminRequests)
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

    private fun page(nextCursor: String?, vararg recipients: SubscribedRecipient): RecipientPage {
        return RecipientPage(recipients = recipients.toList(), nextCursor = nextCursor)
    }

    private fun recipient(recipientId: String, channel: NotificationChannel, vararg keywords: SubscribedKeyword): SubscribedRecipient {
        return SubscribedRecipient(recipientId, channel, keywords.toList().ifEmpty { listOf(nvidia) })
    }

    private fun summaryCommand(version: Long = 1): RouteSummaryCommand {
        return RouteSummaryCommand(
            summaryId = "summary-1",
            storyId = "story-1",
            version = version,
            keywords = listOf("nvidia", "tsmc"),
            title = "NVIDIA and TSMC expand partnership",
            content = "NVIDIA and TSMC announced a new plant.",
            sentiment = SummarySentiment.POSITIVE,
            sourceNewsCount = 3,
            createdAt = now,
        )
    }

    private fun quarantineCommand(): RouteQuarantineCommand {
        return RouteQuarantineCommand(
            eventKey = "quarantine-1:1700000000000",
            storyId = "story-1",
            consecutiveFailures = 3,
            lastFailureReason = "INVALID_RESPONSE",
            quarantinedAt = now,
        )
    }
}
