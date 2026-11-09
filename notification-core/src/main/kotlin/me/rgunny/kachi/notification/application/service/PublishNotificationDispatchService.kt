package me.rgunny.kachi.notification.application.service

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.notification.application.port.inbound.dispatch.PublishNotificationDispatchUseCase
import me.rgunny.kachi.notification.application.port.inbound.dispatch.model.PublishNotificationDispatchResult
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationDispatchPublisher
import me.rgunny.kachi.notification.application.port.outbound.notification.NotificationPublishPersistencePort
import me.rgunny.kachi.notification.application.port.outbound.outbox.NotificationOutboxPersistencePort
import me.rgunny.kachi.notification.domain.NotificationOutbox
import org.slf4j.LoggerFactory

/**
 * Outbox 기반 notification.dispatch 발행 application service.
 * PENDING outbox를 claim해 notification.dispatch로 발행하고 발행 결과를 상태에 반영한다.
 */
class PublishNotificationDispatchService(
    private val outboxPersistencePort: NotificationOutboxPersistencePort,
    private val publishPersistencePort: NotificationPublishPersistencePort,
    private val dispatchPublisher: NotificationDispatchPublisher,
    private val policy: OutboxPublishPolicy,
    private val clock: Clock,
) : PublishNotificationDispatchUseCase {

    override suspend fun publishPending(): PublishNotificationDispatchResult {
        val now = Instant.now(clock)

        // 1. 발행 가능한 PENDING outbox와 오래 잡혀 있던 PUBLISHING outbox를 같은 주기에 처리한다.
        val publishableOutboxes = outboxPersistencePort.findPublishable(now, policy.batchSize)
        val staleThreshold = now.minus(policy.publishingVisibilityTimeout)
        val staleOutboxes = outboxPersistencePort.findStalePublishing(staleThreshold, policy.batchSize)

        // 2. timeout된 PUBLISHING은 이전 publisher가 중단된 것으로 보고 실패 처리해 재시도 대상으로 돌린다.
        // 회수하는 사이에 원래 publisher가 결과를 확정했다면 claim이 달라져 회수되지 않고 집계에서도 빠진다.
        var failed = 0
        var staleRecovered = 0
        staleOutboxes.forEach { outbox ->
            val timedOut = outbox.recordFailure(PUBLISHING_TIMEOUT_REASON, policy.retryPolicy, now)
            val finalized = publishPersistencePort.savePublishFailed(
                outbox = timedOut,
                expectedClaimedAt = claimedAtOf(outbox),
                expectedClaimedBy = claimedByOf(outbox),
                now = now,
                reason = PUBLISHING_TIMEOUT_REASON,
            )
            if (finalized) {
                staleRecovered += 1
                failed += 1
            } else {
                warnDiscardedFinalize(timedOut, claimedAtOf(outbox), claimedByOf(outbox))
            }
        }

        var processed = 0
        var published = 0

        // 3. PENDING outbox는 저장소 CAS로 claim한 publisher만 발행한다.
        publishableOutboxes.forEach { outbox ->
            val claimedOutbox = outboxPersistencePort.claimPublishing(
                outboxId = outbox.id,
                claimedBy = policy.publisherId,
                now = now,
            ) ?: return@forEach

            processed += 1

            // 4. claim에 성공한 outbox payload를 notification.dispatch topic으로 발행한다.
            val publishFailure = runCatching {
                dispatchPublisher.publish(claimedOutbox)
            }.exceptionOrNull()

            if (publishFailure != null) {
                // 6. Kafka publish 실패는 retry 가능한 DB 상태로만 보정한다.
                // 외부 side effect와 DB rollback은 묶을 수 없으므로, 실패 결과 반영만 Mongo transaction으로 확정한다.
                val reason = publishFailure.message?.takeIf { it.isNotBlank() }
                    ?: publishFailure::class.simpleName
                    ?: PUBLISH_FAILED
                val failedOutbox = claimedOutbox.recordFailure(reason, policy.retryPolicy, now)
                val finalized = publishPersistencePort.savePublishFailed(
                    outbox = failedOutbox,
                    expectedClaimedAt = claimedAtOf(claimedOutbox),
                    expectedClaimedBy = claimedByOf(claimedOutbox),
                    now = now,
                    reason = reason,
                )
                if (finalized) {
                    failed += 1
                } else {
                    warnDiscardedFinalize(failedOutbox, claimedAtOf(claimedOutbox), claimedByOf(claimedOutbox))
                }
                return@forEach
            }

            // 5. broker 발행이 끝난 뒤 outbox와 notification의 발행 완료 상태를 같은 DB transaction으로 맞춘다.
            // 발행이 visibility timeout보다 오래 걸려 다른 tick이 이 행을 회수했다면 claim이 달라져 결과가 버려진다.
            val publishedOutbox = claimedOutbox.markPublished(now)
            val finalized = publishPersistencePort.savePublished(
                outbox = publishedOutbox,
                expectedClaimedAt = claimedAtOf(claimedOutbox),
                expectedClaimedBy = claimedByOf(claimedOutbox),
                now = now,
            )
            if (finalized) {
                published += 1
            } else {
                warnDiscardedFinalize(publishedOutbox, claimedAtOf(claimedOutbox), claimedByOf(claimedOutbox))
            }
        }

        // 7. scheduler/admin은 이 결과로 로그와 메트릭만 남긴다.
        return PublishNotificationDispatchResult(
            processed = processed + staleRecovered,
            published = published,
            failed = failed,
            publishTickCompletedAt = now,
        )
    }

    /**
     * claim을 잃어 버려진 결과를 남긴다.
     *
     * 이 행은 다른 tick이 이미 회수했으므로 여기서 되돌릴 것은 없다. 발행이 visibility timeout보다
     * 오래 걸리고 있다는 신호이므로 자주 보이면 timeout이나 batch 크기를 조정해야 한다.
     */
    private fun warnDiscardedFinalize(outbox: NotificationOutbox, claimedAt: Instant, claimedBy: String) {
        log.warn(
            "discarding notification outbox finalize because the claim was lost. outboxId={} claimedAt={} claimedBy={} outboxStatus={}",
            outbox.id.id,
            claimedAt,
            claimedBy,
            outbox.outboxStatus,
        )
    }

    /**
     * 발행 중인 outbox의 claim 시각. 상태와 claim은 함께 움직이므로 PUBLISHING 행에는 반드시 있다.
     */
    private fun claimedAtOf(outbox: NotificationOutbox): Instant {
        return checkNotNull(outbox.claimedAt) { "PUBLISHING outbox must have claimedAt. outboxId=${outbox.id}" }
    }

    private fun claimedByOf(outbox: NotificationOutbox): String {
        return checkNotNull(outbox.claimedBy) { "PUBLISHING outbox must have claimedBy. outboxId=${outbox.id}" }
    }

    private companion object {
        val log = LoggerFactory.getLogger(PublishNotificationDispatchService::class.java)
        const val PUBLISHING_TIMEOUT_REASON = "publishing-timeout"
        const val PUBLISH_FAILED = "publish-failed"
    }
}
