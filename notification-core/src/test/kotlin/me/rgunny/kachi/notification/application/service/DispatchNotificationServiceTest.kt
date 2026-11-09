package me.rgunny.kachi.notification.application.service

import me.rgunny.kachi.notification.application.port.inbound.dispatch.model.DispatchFailureClassification
import me.rgunny.kachi.notification.application.port.inbound.dispatch.model.DispatchNotificationCommand
import me.rgunny.kachi.notification.application.port.outbound.recipient.model.RecipientUnavailableReason
import me.rgunny.kachi.notification.application.port.outbound.recipient.model.UnavailableRecipient
import me.rgunny.kachi.notification.application.port.outbound.sender.model.SendNotificationResult
import me.rgunny.kachi.notification.domain.Notification
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationId
import me.rgunny.kachi.notification.domain.NotificationStatus
import me.rgunny.kachi.notification.exception.dispatch.DispatchNotReadyException
import me.rgunny.kachi.notification.exception.recipient.RecipientResolveException
import me.rgunny.kachi.notification.exception.sender.NonRetryableSendException
import me.rgunny.kachi.notification.exception.sender.RetryableSendException
import me.rgunny.kachi.notification.fake.FakeDeduplicationPort
import me.rgunny.kachi.notification.fake.FakeIdempotencyKeyPort
import me.rgunny.kachi.notification.fake.FakeNotificationDispatchPersistencePort
import me.rgunny.kachi.notification.fake.FakeNotificationPersistencePort
import me.rgunny.kachi.notification.fake.FakeRecipientResolverPort
import me.rgunny.kachi.notification.fake.FakeSender
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.CLOCK
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.DEDUPE_TTL
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.IDEMPOTENCY_KEY_TTL
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.MESSAGE
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.NOW
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.RECIPIENT_ID
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.REQUESTER
import me.rgunny.kachi.notification.fixture.NotificationTestFixture.REQUEST_ID
import me.rgunny.kachi.notification.domain.retry.FailureCategory
import me.rgunny.kachi.notification.domain.retry.RetryFailure
import me.rgunny.kachi.notification.domain.retry.RetryFailureCode
import me.rgunny.kachi.notification.domain.retry.RetryPolicy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.rgunny.kachi.notification.domain.NotificationOrigin

@DisplayName("DispatchNotificationService")
class DispatchNotificationServiceTest {
    private val now = NOW
    private val clock = CLOCK

    @Test
    @DisplayName("중복 dispatch는 현재 상태를 반환하고 sender를 호출하지 않는다")
    fun duplicatedDispatch() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort(acquireResult = false)
        val sender = FakeSender()
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.PUBLISHED, result.status)
        assertTrue(result.duplicated)
        assertFalse(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.NONE, result.failureClassification)
        assertEquals(0, sender.sendCount)
    }

    @Test
    @DisplayName("중복 dispatch는 수신 주소를 조회하지 않는다")
    fun duplicatedDispatchDoesNotResolveRecipient() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val resolver = FakeRecipientResolverPort()
        val service = service(persistence, FakeDeduplicationPort(acquireResult = false), FakeIdempotencyKeyPort(), FakeSender(), resolver = resolver)

        service.dispatch(command(notification.id))

        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    @DisplayName("claim에 실패하면 수신 주소를 조회하지 않는다")
    fun claimFailureDoesNotResolveRecipient() = runSuspend {
        val notification = publishedNotification().markProcessing(now.minusSeconds(1), "other-worker")
        val persistence = FakeNotificationPersistencePort().also {
            it.put(notification)
            it.claimPublishedEnabled = false
        }
        val resolver = FakeRecipientResolverPort()
        val service = service(persistence, FakeDeduplicationPort(), FakeIdempotencyKeyPort(), FakeSender(), resolver = resolver)

        val result = service.dispatch(command(notification.id))

        assertTrue(result.duplicated)
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    @DisplayName("claim한 알림의 recipientId와 channel로 수신 주소를 조회하고 그 주소를 sender에 넘긴다")
    fun resolveRecipientWithClaimedNotification() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val resolver = FakeRecipientResolverPort()
        val sender = FakeSender()
        val service = service(persistence, FakeDeduplicationPort(), FakeIdempotencyKeyPort(), sender, resolver = resolver)

        service.dispatch(command(notification.id))

        assertEquals(listOf(RECIPIENT_ID to NotificationChannel.SLACK), resolver.calls)
        assertEquals(FakeRecipientResolverPort.ADDRESS, sender.commands.single().address)
    }

    @Test
    @DisplayName("수신 주소가 없으면 sender와 idempotency key를 호출하지 않고 SUPPRESSED로 끝낸다")
    fun suppressWhenRecipientUnavailable() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val idempotency = FakeIdempotencyKeyPort()
        val sender = FakeSender()
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = idempotency,
            sender = sender,
            resolver = FakeRecipientResolverPort(result = UnavailableRecipient(RecipientUnavailableReason.REVOKED)),
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.SUPPRESSED, result.status)
        assertFalse(result.duplicated)
        assertFalse(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.NONE, result.failureClassification)
        assertEquals(null, result.failure)
        assertEquals(0, sender.sendCount)
        assertEquals(0, idempotency.callCount)
        val saved = persistence.saved.last()
        assertEquals(NotificationStatus.SUPPRESSED, saved.status)
        assertEquals("recipient unavailable: REVOKED", saved.failureReason)
        assertEquals(0, saved.dispatchAttempts)
        assertEquals(null, saved.claimedAt)
        assertEquals(null, saved.claimedBy)
        assertTrue(deduplication.releasedKeys.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(RecipientUnavailableReason::class)
    @DisplayName("수신 주소가 없는 모든 사유는 SUPPRESSED로 끝낸다")
    fun suppressForEveryUnavailableReason(reason: RecipientUnavailableReason) = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val service = service(
            persistence = persistence,
            deduplication = FakeDeduplicationPort(),
            idempotency = FakeIdempotencyKeyPort(),
            sender = FakeSender(),
            resolver = FakeRecipientResolverPort(result = UnavailableRecipient(reason)),
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.SUPPRESSED, result.status)
        assertEquals("recipient unavailable: $reason", persistence.saved.last().failureReason)
    }

    @Test
    @DisplayName("SUPPRESSED 저장의 claim 조건이 불일치하면 stale 결과로 종료한다")
    fun suppressStaleFinalize() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val dispatchPersistence = FakeNotificationDispatchPersistencePort(persistence).also {
            it.forceClaimMismatch = true
        }
        val service = service(
            persistence = persistence,
            deduplication = FakeDeduplicationPort(),
            idempotency = FakeIdempotencyKeyPort(),
            sender = FakeSender(),
            dispatchPersistence = dispatchPersistence,
            resolver = FakeRecipientResolverPort(result = UnavailableRecipient(RecipientUnavailableReason.NOT_FOUND)),
        )

        val result = service.dispatch(command(notification.id))

        assertTrue(result.duplicated)
        assertFalse(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.NONE, result.failureClassification)
        assertEquals(emptyList(), persistence.saved)
    }

    @Test
    @DisplayName("수신 주소 조회가 실패하면 발송 시도 없이 RETRY_WAIT로 완료하고 dedupe를 해제한다")
    fun recipientResolveFailureRetries() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender()
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = FakeIdempotencyKeyPort(),
            sender = sender,
            maxAttempts = 2,
            resolver = FakeRecipientResolverPort(failure = resolveException(RetryFailureCode.RECIPIENT_RESOLVE_FAILED)),
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.RETRY_WAIT, result.status)
        assertFalse(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.RETRYABLE, result.failureClassification)
        assertEquals(RetryFailureCode.RECIPIENT_RESOLVE_FAILED.code, result.failure?.code)
        assertEquals("recipient resolve failed", persistence.saved.last().failureReason)
        assertEquals(1, persistence.saved.last().dispatchAttempts)
        assertEquals(0, sender.sendCount)
        assertEquals(listOf("notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("수신 주소 조회 실패가 재시도 한도에 도달하면 DEAD로 완료한다")
    fun recipientResolveFailureExhausted() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val service = service(
            persistence = persistence,
            deduplication = FakeDeduplicationPort(),
            idempotency = FakeIdempotencyKeyPort(),
            sender = FakeSender(),
            maxAttempts = 1,
            resolver = FakeRecipientResolverPort(failure = resolveException(RetryFailureCode.RECIPIENT_RESOLVE_FAILED)),
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.DEAD, result.status)
        assertEquals(DispatchFailureClassification.NON_RETRYABLE, result.failureClassification)
    }

    @Test
    @DisplayName("수신 주소 조회 지연은 TIMEOUT 분류로 RETRY_WAIT가 된다")
    fun recipientResolveTimeout() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val service = service(
            persistence = persistence,
            deduplication = FakeDeduplicationPort(),
            idempotency = FakeIdempotencyKeyPort(),
            sender = FakeSender(),
            resolver = FakeRecipientResolverPort(failure = resolveException(RetryFailureCode.RECIPIENT_RESOLVE_TIMEOUT)),
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.RETRY_WAIT, result.status)
        assertEquals(FailureCategory.TIMEOUT, result.failure?.category)
    }

    @Test
    @DisplayName("수신 주소 조회 중 예상 밖 예외가 나면 dedupe를 해제하고 예외를 전파한다")
    fun recipientResolveUnexpectedException() {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = FakeIdempotencyKeyPort(),
            sender = FakeSender(),
            resolver = FakeRecipientResolverPort(failure = IllegalStateException("resolver-down")),
        )

        assertFailsWith<IllegalStateException> {
            runSuspend { service.dispatch(command(notification.id)) }
        }

        assertEquals(listOf("notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("sent 마커 선점에 실패하면 sender를 부르지 않고 SUPPRESSED(already sent)로 끝낸다")
    fun suppressWhenAlreadySent() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort(acquireResults = mapOf(SENT_GUARD_KEY to false))
        val idempotency = FakeIdempotencyKeyPort()
        val sender = FakeSender()
        val service = service(persistence, deduplication, idempotency, sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.SUPPRESSED, result.status)
        assertFalse(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.NONE, result.failureClassification)
        assertEquals("already sent", persistence.saved.last().failureReason)
        assertEquals(0, sender.sendCount)
        assertEquals(1, idempotency.callCount)
        assertTrue(deduplication.releasedKeys.isEmpty())
    }

    @Test
    @DisplayName("sent 마커는 idempotency key TTL로 선점하고 SENT 뒤에는 풀지 않는다")
    fun keepSentGuardAfterSent() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), FakeSender())

        service.dispatch(command(notification.id))

        assertEquals(
            listOf("notification:dispatch:${notification.id.id}" to DEDUPE_TTL, SENT_GUARD_KEY to IDEMPOTENCY_KEY_TTL),
            deduplication.acquiredKeys,
        )
        assertTrue(deduplication.releasedKeys.isEmpty())
    }

    @Test
    @DisplayName("RETRY_WAIT로 확정하면 sent 마커도 푼다")
    fun releaseSentGuardOnRetryWait() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(result = SendNotificationResult.TransientFailure(timeoutFailure()))
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), sender, maxAttempts = 2)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.RETRY_WAIT, result.status)
        assertEquals(listOf(SENT_GUARD_KEY, "notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("DEAD로 확정하면 sent 마커를 풀지 않는다")
    fun keepSentGuardOnDead() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(result = SendNotificationResult.PermanentFailure(invalidRecipientFailure()))
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.DEAD, result.status)
        assertTrue(deduplication.releasedKeys.isEmpty())
    }

    @Test
    @DisplayName("sender 호출 중 예상 밖 예외가 나면 sent 마커와 dedupe를 모두 푼다")
    fun releaseSentGuardOnUnexpectedSenderException() {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(failure = IllegalStateException("sender-down"))
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), sender)

        assertFailsWith<IllegalStateException> {
            runSuspend { service.dispatch(command(notification.id)) }
        }

        assertEquals(listOf(SENT_GUARD_KEY, "notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("수신 주소가 없거나 조회에 실패하면 sent 마커를 선점하지 않는다")
    fun doNotAcquireSentGuardBeforeSend() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = FakeIdempotencyKeyPort(),
            sender = FakeSender(),
            resolver = FakeRecipientResolverPort(result = UnavailableRecipient(RecipientUnavailableReason.REVOKED)),
        )

        service.dispatch(command(notification.id))

        assertEquals(listOf("notification:dispatch:${notification.id.id}" to DEDUPE_TTL), deduplication.acquiredKeys)
    }

    @Test
    @DisplayName("PUBLISHED 알림 claim 후 발송 성공이면 SENT로 완료한다")
    fun dispatchSuccess() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val sender = FakeSender(result = SendNotificationResult.Success("provider-1"))
        val service = service(persistence, FakeDeduplicationPort(), FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.SENT, result.status)
        assertFalse(result.duplicated)
        assertTrue(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.NONE, result.failureClassification)
        assertEquals(NotificationStatus.SENT, persistence.saved.last().status)
        assertEquals(1, persistence.saved.last().dispatchAttempts)
        assertEquals(1, sender.sendCount)
        assertEquals("vendor-key", sender.commands.single().idempotencyKey)
        assertEquals(FakeRecipientResolverPort.ADDRESS, sender.commands.single().address)
    }

    @Test
    @DisplayName("finalize CAS 조건이 불일치하면 Kafka retry 대상이 아닌 stale 결과로 종료한다")
    fun staleFinalizeDoesNotRetry() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val dispatchPersistence = FakeNotificationDispatchPersistencePort(persistence).also {
            it.forceClaimMismatch = true
        }
        val sender = FakeSender(result = SendNotificationResult.Success("provider-1"))
        val service = service(
            persistence = persistence,
            deduplication = FakeDeduplicationPort(),
            idempotency = FakeIdempotencyKeyPort(),
            sender = sender,
            dispatchPersistence = dispatchPersistence,
        )

        val result = service.dispatch(command(notification.id))

        assertTrue(result.duplicated)
        assertTrue(result.dispatchAttempted)
        assertEquals(DispatchFailureClassification.NONE, result.failureClassification)
        assertEquals(emptyList(), persistence.saved)
        assertEquals(1, sender.sendCount)
    }

    @Test
    @DisplayName("PUBLISHED claim 실패 후 RETRY_WAIT claim에 성공하면 발송한다")
    fun dispatchFromRetryWait() = runSuspend {
        val notification = retryWaitNotification()
        val persistence = FakeNotificationPersistencePort().also {
            it.put(notification)
            it.claimPublishedEnabled = false
        }
        val sender = FakeSender(result = SendNotificationResult.Success())
        val service = service(persistence, FakeDeduplicationPort(), FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.SENT, result.status)
        assertEquals(1, persistence.retryWaitClaimCount)
    }

    @Test
    @DisplayName("rate limit과 transient failure는 재시도 가능하면 RETRY_WAIT로 완료하고 dedupe를 해제한다")
    fun retryableFailure() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(result = SendNotificationResult.RateLimited(rateLimitedFailure()))
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = FakeIdempotencyKeyPort(),
            sender = sender,
            maxAttempts = 2,
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.RETRY_WAIT, result.status)
        assertEquals("vendor rate limited", persistence.saved.last().failureReason)
        assertEquals(DispatchFailureClassification.RETRYABLE, result.failureClassification)
        assertEquals(RetryFailureCode.VENDOR_RATE_LIMITED.code, result.failure?.code)
        assertEquals(1, persistence.saved.last().dispatchAttempts)
        assertEquals(listOf(SENT_GUARD_KEY, "notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("재시도 가능한 실패라도 한도에 도달하면 DEAD로 완료하고 dedupe를 유지한다")
    fun retryableFailureExhausted() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(result = SendNotificationResult.TransientFailure(timeoutFailure()))
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = FakeIdempotencyKeyPort(),
            sender = sender,
            maxAttempts = 1,
        )

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.DEAD, result.status)
        assertEquals("vendor timeout", persistence.saved.last().failureReason)
        assertEquals(DispatchFailureClassification.NON_RETRYABLE, result.failureClassification)
        assertEquals(RetryFailureCode.VENDOR_TIMEOUT.code, result.failure?.code)
        assertTrue(deduplication.releasedKeys.isEmpty())
    }

    @Test
    @DisplayName("permanent failure는 DEAD로 완료한다")
    fun permanentFailure() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val sender = FakeSender(result = SendNotificationResult.PermanentFailure(invalidRecipientFailure()))
        val service = service(persistence, FakeDeduplicationPort(), FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.DEAD, result.status)
        assertEquals("invalid recipient", persistence.saved.last().failureReason)
        assertEquals(DispatchFailureClassification.NON_RETRYABLE, result.failureClassification)
        assertEquals(RetryFailureCode.INVALID_RECIPIENT.code, result.failure?.code)
    }

    @Test
    @DisplayName("claim 실패 상태가 REQUESTED면 dedupe를 해제하고 예외를 전파한다")
    fun requestedNotificationIsNotReady() {
        val notification = requestedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), FakeSender())

        assertFailsWith<DispatchNotReadyException> {
            runSuspend { service.dispatch(command(notification.id)) }
        }

        assertEquals(listOf("notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("claim에 실패했는데 상태가 여전히 PUBLISHED면 중복이 아니라 재시도 신호를 낸다")
    fun claimMissOnPublishedNotificationIsNotReady() {
        // 발행 완료 반영 transaction이 커밋되기 전에 dispatch 레코드가 먼저 닿으면 claim은 실패하고 재조회는 PUBLISHED를 본다.
        // 이를 중복으로 보고 ack하면 알림이 PUBLISHED에 영구히 남는다(ADR 029).
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also {
            it.put(notification)
            it.claimPublishedEnabled = false
        }
        val deduplication = FakeDeduplicationPort()
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), FakeSender())

        assertFailsWith<DispatchNotReadyException> {
            runSuspend { service.dispatch(command(notification.id)) }
        }

        assertEquals(listOf("notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
        assertEquals(emptyList(), persistence.saved)
    }

    @Test
    @DisplayName("sender 호출 중 예외가 발생하면 dedupe를 해제하고 예외를 전파한다")
    fun senderException() {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(failure = IllegalStateException("sender-down"))
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), sender)

        assertFailsWith<IllegalStateException> {
            runSuspend { service.dispatch(command(notification.id)) }
        }

        assertEquals(listOf(SENT_GUARD_KEY, "notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("sender가 retryable 예외를 던지면 RETRY_WAIT로 완료한다")
    fun senderRetryableException() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(
            failure = RetryableSendException(
                notificationId = notification.id,
                channel = NotificationChannel.SLACK,
                failure = timeoutFailure(),
            )
        )
        val service = service(persistence, deduplication, FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.RETRY_WAIT, result.status)
        assertEquals("vendor timeout", persistence.saved.last().failureReason)
        assertEquals(DispatchFailureClassification.RETRYABLE, result.failureClassification)
        assertEquals(listOf(SENT_GUARD_KEY, "notification:dispatch:${notification.id.id}"), deduplication.releasedKeys)
    }

    @Test
    @DisplayName("재시도 가능 실패 저장에 실패하면 dedupe를 해제하지 않는다")
    fun doNotReleaseDedupeWhenRetryableFailureSaveFails() {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also {
            it.put(notification)
            it.saveFailure = IllegalStateException("mongo-down")
        }
        val deduplication = FakeDeduplicationPort()
        val sender = FakeSender(result = SendNotificationResult.RateLimited(rateLimitedFailure()))
        val service = service(
            persistence = persistence,
            deduplication = deduplication,
            idempotency = FakeIdempotencyKeyPort(),
            sender = sender,
            maxAttempts = 2,
        )

        assertFailsWith<IllegalStateException> {
            runSuspend { service.dispatch(command(notification.id)) }
        }

        assertTrue(deduplication.releasedKeys.isEmpty())
    }

    @Test
    @DisplayName("sender가 non-retryable 예외를 던지면 DEAD로 완료한다")
    fun senderNonRetryableException() = runSuspend {
        val notification = publishedNotification()
        val persistence = FakeNotificationPersistencePort().also { it.put(notification) }
        val sender = FakeSender(
            failure = NonRetryableSendException(
                notificationId = notification.id,
                channel = NotificationChannel.SLACK,
                failure = invalidRecipientFailure(),
            )
        )
        val service = service(persistence, FakeDeduplicationPort(), FakeIdempotencyKeyPort(), sender)

        val result = service.dispatch(command(notification.id))

        assertEquals(NotificationStatus.DEAD, result.status)
        assertEquals("invalid recipient", persistence.saved.last().failureReason)
        assertEquals(DispatchFailureClassification.NON_RETRYABLE, result.failureClassification)
    }

    private fun service(
        persistence: FakeNotificationPersistencePort,
        deduplication: FakeDeduplicationPort,
        idempotency: FakeIdempotencyKeyPort,
        sender: FakeSender,
        maxAttempts: Int = 3,
        dispatchPersistence: FakeNotificationDispatchPersistencePort = FakeNotificationDispatchPersistencePort(persistence),
        resolver: FakeRecipientResolverPort = FakeRecipientResolverPort(),
    ): DispatchNotificationService {
        return DispatchNotificationService(
            notificationPersistencePort = persistence,
            dispatchPersistencePort = dispatchPersistence,
            deduplicationPort = deduplication,
            recipientResolverPort = resolver,
            idempotencyKeyPort = idempotency,
            senderRouter = NotificationSenderRouter(listOf(sender)),
            policy = DispatchNotificationPolicy(
                workerId = "worker-1",
                dedupeTtl = DEDUPE_TTL,
                idempotencyKeyTtl = IDEMPOTENCY_KEY_TTL,
                retryPolicy = RetryPolicy(
                    maxAttempts = maxAttempts,
                    baseDelay = Duration.ofSeconds(10),
                    maxDelay = Duration.ofMinutes(1),
                ),
                processingVisibilityTimeout = Duration.ofSeconds(30),
                recoveryBatchSize = 10,
            ),
            clock = clock,
        )
    }

    private fun requestedNotification(): Notification {
        return Notification.request(
            requestId = REQUEST_ID,
            requester = REQUESTER,
            channel = NotificationChannel.SLACK,
            recipientId = RECIPIENT_ID,
            message = MESSAGE,
            origin = NotificationOrigin.NONE,
            now = now.minusSeconds(10),
        )
    }

    private fun publishedNotification(): Notification {
        return requestedNotification().markPublished(now.minusSeconds(5))
    }

    private fun retryWaitNotification(): Notification {
        return publishedNotification()
            .markProcessing(now.minusSeconds(4), "worker-1")
            .markFailed(now.minusSeconds(3), "rate-limited")
            .markRetryWait(now.minusSeconds(2), "rate-limited")
    }

    private fun timeoutFailure(): RetryFailure {
        return RetryFailure.of(RetryFailureCode.VENDOR_TIMEOUT)
    }

    private fun rateLimitedFailure(): RetryFailure {
        return RetryFailure.of(RetryFailureCode.VENDOR_RATE_LIMITED)
    }

    private fun invalidRecipientFailure(): RetryFailure {
        return RetryFailure.of(RetryFailureCode.INVALID_RECIPIENT)
    }

    private companion object {
        const val SENT_GUARD_KEY = "notification:sent:$REQUEST_ID"
    }

    private fun resolveException(code: RetryFailureCode): RecipientResolveException {
        return RecipientResolveException(
            recipientId = RECIPIENT_ID,
            channel = NotificationChannel.SLACK,
            failure = RetryFailure.of(code),
        )
    }

    private fun command(notificationId: NotificationId): DispatchNotificationCommand {
        return DispatchNotificationCommand(
            notificationId = notificationId,
            requestId = REQUEST_ID,
            channel = NotificationChannel.SLACK,
            recipientId = RECIPIENT_ID,
            message = MESSAGE,
        )
    }
}
