package me.rgunny.kachi.collector.application.service.outbox

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import me.rgunny.kachi.collector.application.exception.CollectorOutboxPublishException
import me.rgunny.kachi.collector.application.port.inbound.outbox.RelayCollectorOutboxUseCase
import me.rgunny.kachi.collector.application.port.inbound.outbox.model.RelayCollectorOutboxResult
import me.rgunny.kachi.collector.application.port.outbound.outbox.CollectorOutboxPersistencePort
import me.rgunny.kachi.collector.application.port.outbound.outbox.CollectorOutboxPublisherPort
import me.rgunny.kachi.collector.domain.outbox.CollectorOutbox
import me.rgunny.kachi.collector.domain.outbox.CollectorOutboxClaim
import me.rgunny.kachi.collector.domain.outbox.CollectorOutboxStatus
import org.slf4j.LoggerFactory

/**
 * 기록된 outbox 이벤트를 주기적으로 발행하고 그 결과를 확정하는 유스케이스.
 *
 * 발행은 "소유권을 잡는다 -> 내보낸다 -> 결과를 확정한다" 세 단계이고 각 단계가 단일 문서 조건부 쓰기다.
 * 여러 인스턴스가 같은 행을 집어도 하나만 소유권을 얻고, 확정도 그 소유권이 그대로일 때만 저장된다.
 *
 * 발행 도중 인스턴스가 멈추면 행은 발행 중인 채로 남는다. 다음 tick이 그 행을 회수해 다시 발행 대상으로 돌린다.
 * 재시도는 여기서 기다리지 않고 다음 tick으로 넘긴다. tick 안에서 다시 호출하는 루프는 두지 않는다.
 *
 * 빈 등록은 relay 설정이 켜졌을 때만 이루어지며 그 조건은 config 계층이 갖는다.
 */
class RelayCollectorOutboxService(
    private val outboxPersistencePort: CollectorOutboxPersistencePort,
    private val publisherPort: CollectorOutboxPublisherPort,
    private val policy: CollectorOutboxRelayPolicy,
    private val clock: Clock
) : RelayCollectorOutboxUseCase {

    override suspend fun relay(): RelayCollectorOutboxResult {
        // 1. 조회 기준과 전이 시각을 한 tick 안에서 일치시키기 위해 시작 시각을 한 번만 읽는다.
        val now = Instant.now(clock)

        // 2. 발행 중에 멈춘 인스턴스가 남긴 행을 먼저 회수한다.
        // 회수된 행의 다음 차례는 backoff만큼 미래라 이번 tick의 발행 대상에는 들어오지 않는다.
        val recovered = outboxPersistencePort.findStalePublishing(
            threshold = now.minus(policy.publishingVisibilityTimeout),
            batchSize = policy.batchSize
        ).mapNotNull { recoverStale(it, now) }

        // 3. 발행할 차례가 된 행의 소유권을 잡고 내보낸다.
        // 소유권을 잡은 행은 확정에 실패하더라도 이번 tick이 다룬 행으로 센다.
        var claimedCount = 0
        val finalized = outboxPersistencePort.findPublishable(
            now = now,
            batchSize = policy.batchSize
        ).mapNotNull { outbox ->
            val claimed = claim(outbox, now) ?: return@mapNotNull null
            claimedCount += 1

            publish(claimed, now)
        }

        // 4. 완료 시각은 발행이 모두 끝난 뒤의 시각이다. tick이 오래 걸리면 시작 시각과 크게 벌어진다.
        return RelayCollectorOutboxResult(
            processed = claimedCount + recovered.size,
            published = finalized.count { it.status == CollectorOutboxStatus.PUBLISHED },
            retried = finalized.count { it.status == CollectorOutboxStatus.PENDING },
            dead = (finalized + recovered).count { it.status == CollectorOutboxStatus.DEAD },
            staleRecovered = recovered.size,
            completedAt = Instant.now(clock)
        )
    }

    /**
     * 발행 시간을 넘긴 행에 실패를 기록해 다시 발행 대상으로 되돌린다.
     *
     * 확정 조건은 원래 소유자의 소유권이다. 회수하는 사이에 원래 소유자가 결과를 확정했다면 조건이 어긋나 회수되지 않는다.
     */
    private suspend fun recoverStale(outbox: CollectorOutbox, now: Instant): CollectorOutbox? {
        return catchingRow(outbox) {
            val claim = claimOf(outbox)
            val next = outbox.recordFailure(STALE_FAILURE_REASON, policy.retryPolicy, now)

            log.warn(
                "Recovering stale publishing outbox: id={}, claimedBy={}, claimedAt={}, status={}",
                outbox.id.value,
                claim.claimedBy,
                claim.claimedAt,
                next.status
            )

            finalize(next, claim)
        }
    }

    /**
     * 저장소의 조건부 쓰기로 소유권을 잡는다.
     *
     * null은 다른 인스턴스가 먼저 잡았거나 조회 이후 차례가 바뀌었다는 뜻이며, 이 행은 이번 tick에서 다루지 않는다.
     */
    private suspend fun claim(outbox: CollectorOutbox, now: Instant): CollectorOutbox? {
        return catchingRow(outbox) {
            val claimed = outboxPersistencePort.claimPublishing(outbox.id, policy.publisherId, now)
            if (claimed == null) {
                log.debug("Skipping outbox already taken by another relay: id={}", outbox.id.value)
            }

            claimed
        }
    }

    /**
     * 소유권을 잡은 행을 내보내고 그 결과를 확정한다.
     *
     * 발행 실패는 예외로 올리지 않고 재시도 예약이나 DEAD 전이로 바꿔 저장한다. 코루틴 취소만 그대로 전파한다.
     */
    private suspend fun publish(outbox: CollectorOutbox, now: Instant): CollectorOutbox? {
        val next = try {
            publisherPort.publish(outbox)
            log.info(
                "Published outbox event: id={}, type={}, eventKey={}, retryCount={}",
                outbox.id.value,
                outbox.eventType,
                outbox.eventKey,
                outbox.retryCount
            )

            outbox.markPublished(now)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed(outbox, error, now)
        }

        return catchingRow(outbox) { finalize(next, claimOf(outbox)) }
    }

    /**
     * 발행 실패를 다음 상태로 옮긴다. 재시도해도 결과가 같은 실패만 한도와 무관하게 DEAD로 보낸다.
     */
    private fun failed(outbox: CollectorOutbox, error: Exception, now: Instant): CollectorOutbox {
        val reason = reasonOf(error)

        if (error is CollectorOutboxPublishException && !error.retryable) {
            log.error(
                "Outbox event cannot be published and is moved to dead immediately: id={}, type={}, retryCount={}, reason={}",
                outbox.id.value,
                outbox.eventType,
                outbox.retryCount,
                reason,
                error
            )

            return outbox.markDead(reason, now)
        }

        val next = outbox.recordFailure(reason, policy.retryPolicy, now)
        if (next.status == CollectorOutboxStatus.DEAD) {
            log.error(
                "Outbox event exhausted publish retries: id={}, type={}, retryCount={}, reason={}",
                outbox.id.value,
                outbox.eventType,
                next.retryCount,
                reason,
                error
            )
        } else {
            log.warn(
                "Outbox event publish failed and is scheduled for retry: id={}, type={}, retryCount={}, nextRetryAt={}, reason={}",
                outbox.id.value,
                outbox.eventType,
                next.retryCount,
                next.nextRetryAt,
                reason,
                error
            )
        }

        return next
    }

    /**
     * 소유권이 그대로일 때만 결과를 저장한다. 회수된 뒤 돌아온 결과는 저장되지 않고 집계에서도 빠진다.
     */
    private suspend fun finalize(outbox: CollectorOutbox, claim: CollectorOutboxClaim): CollectorOutbox? {
        if (outboxPersistencePort.finalize(outbox, claim)) {
            return outbox
        }

        log.warn(
            "Discarding outbox result because the claim was lost: id={}, claimedBy={}, claimedAt={}, status={}",
            outbox.id.value,
            claim.claimedBy,
            claim.claimedAt,
            outbox.status
        )

        return null
    }

    /**
     * 발행 중인 행의 소유권. 상태와 소유권은 함께 움직이므로 발행 중인 행에는 반드시 있다.
     */
    private fun claimOf(outbox: CollectorOutbox): CollectorOutboxClaim {
        return checkNotNull(outbox.claim) { "발행 중인 outbox에는 소유권이 있어야 합니다: ${outbox.id.value}" }
    }

    /**
     * 저장소 실패가 tick 전체를 멈추지 않도록 행 단위로 격리한다.
     *
     * 확정하지 못한 행은 발행 중인 채로 남아 다음 tick의 회수 대상이 되므로 여기서 되돌릴 것은 없다.
     * 코루틴 취소는 tick을 그만두라는 신호라 그대로 전파하고, JVM 오류(Error)는 잡지 않는다.
     */
    private suspend fun <T> catchingRow(outbox: CollectorOutbox, block: suspend () -> T): T? {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error(
                "Outbox relay skipped a row after a storage failure: id={}, eventKey={}",
                outbox.id.value,
                outbox.eventKey,
                error
            )

            null
        }
    }

    /**
     * 실패 사유 문자열. 메시지가 없으면 예외 이름으로, 이름조차 없는 익명 예외면 고정값으로 남긴다.
     */
    private fun reasonOf(error: Exception): String {
        return error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName?.takeIf { it.isNotBlank() }
            ?: UNKNOWN_FAILURE_REASON
    }

    private companion object {
        val log = LoggerFactory.getLogger(RelayCollectorOutboxService::class.java)

        const val STALE_FAILURE_REASON = "publishing-timeout"
        const val UNKNOWN_FAILURE_REASON = "publish-failed"
    }
}
