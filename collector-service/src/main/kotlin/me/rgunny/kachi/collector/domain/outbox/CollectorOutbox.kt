package me.rgunny.kachi.collector.domain.outbox

import java.time.Instant

/**
 * 발행해야 할 도메인 이벤트 1건과 그 발행 상태.
 *
 * 도메인 상태 변경과 이벤트 기록을 한 트랜잭션에 묶어, 상태는 바뀌었는데 알림이 없는 경우를 없앤다.
 *
 * ```
 * PENDING ──claim──▶ PUBLISHING ──publish ok──▶ PUBLISHED (terminal)
 *                        │
 *                        ├── retryable 실패, 한도 미만 ──▶ PENDING
 *                        ├── retryable 실패, 한도 도달 ──▶ DEAD
 *                        └── non-retryable 실패 ───────▶ DEAD
 * DEAD ──recover(운영자)──▶ PENDING
 * ```
 *
 * 불변이므로 전이 메서드는 새 인스턴스를 반환한다. 상태에 맞지 않는 전이 요청은 예외를 던진다.
 * "이미 PUBLISHED면 무시" 같은 관대한 처리는 두지 않는다. 늦은 결과를 거르는 것은 저장소의 CAS 몫이다.
 */
class CollectorOutbox private constructor(
    val id: CollectorOutboxId,
    val eventType: CollectorOutboxEventType,
    val eventKey: String,
    val partitionKey: String,
    val payload: String,
    val status: CollectorOutboxStatus,
    val retryCount: Int,
    val nextRetryAt: Instant,
    val lastError: String?,
    val publishedAt: Instant?,
    val claim: CollectorOutboxClaim?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {

        fun create(
            eventType: CollectorOutboxEventType,
            eventKey: String,
            partitionKey: String,
            payload: String,
            now: Instant
        ): CollectorOutbox {
            require(eventKey.isNotBlank()) { "outbox eventKey는 비어 있을 수 없습니다" }
            require(partitionKey.isNotBlank()) { "outbox partitionKey는 비어 있을 수 없습니다" }
            require(payload.isNotBlank()) { "outbox payload는 비어 있을 수 없습니다" }

            return CollectorOutbox(
                id = CollectorOutboxId.newId(),
                eventType = eventType,
                eventKey = eventKey,
                partitionKey = partitionKey,
                payload = payload,
                status = CollectorOutboxStatus.PENDING,
                retryCount = 0,
                // 생성 즉시 발행 대상이다. 첫 tick이 바로 집어가도록 now로 둔다.
                nextRetryAt = now,
                lastError = null,
                publishedAt = null,
                claim = null,
                createdAt = now,
                updatedAt = now
            )
        }

        fun restore(
            id: CollectorOutboxId,
            eventType: CollectorOutboxEventType,
            eventKey: String,
            partitionKey: String,
            payload: String,
            status: CollectorOutboxStatus,
            retryCount: Int,
            nextRetryAt: Instant,
            lastError: String?,
            publishedAt: Instant?,
            claim: CollectorOutboxClaim?,
            createdAt: Instant,
            updatedAt: Instant
        ): CollectorOutbox {
            require(eventKey.isNotBlank()) { "outbox eventKey는 비어 있을 수 없습니다" }
            require(partitionKey.isNotBlank()) { "outbox partitionKey는 비어 있을 수 없습니다" }
            require(payload.isNotBlank()) { "outbox payload는 비어 있을 수 없습니다" }
            require(retryCount >= 0) { "outbox retryCount는 0 이상이어야 합니다" }
            require(lastError == null || lastError.isNotBlank()) { "outbox lastError는 비어 있을 수 없습니다" }
            // claim은 PUBLISHING의 소유권이므로 상태와 반드시 함께 움직인다.
            require((status == CollectorOutboxStatus.PUBLISHING) == (claim != null)) {
                "outbox claim은 PUBLISHING 상태에서만 존재해야 합니다"
            }
            require(status != CollectorOutboxStatus.PUBLISHED || publishedAt != null) {
                "PUBLISHED outbox는 publishedAt을 가져야 합니다"
            }

            return CollectorOutbox(
                id = id,
                eventType = eventType,
                eventKey = eventKey,
                partitionKey = partitionKey,
                payload = payload,
                status = status,
                retryCount = retryCount,
                nextRetryAt = nextRetryAt,
                lastError = lastError,
                publishedAt = publishedAt,
                claim = claim,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }

    /**
     * 발행을 시작하며 소유권을 잡는다. 실제 상호배제는 저장소의 CAS가 하고 여기서는 상태만 옮긴다.
     */
    fun markPublishing(now: Instant, claimedBy: String): CollectorOutbox {
        check(status == CollectorOutboxStatus.PENDING) { "PENDING outbox만 발행을 시작할 수 있습니다: $status" }

        return transition(
            status = CollectorOutboxStatus.PUBLISHING,
            claim = CollectorOutboxClaim(claimedBy = claimedBy, claimedAt = now),
            updatedAt = now
        )
    }

    fun markPublished(now: Instant): CollectorOutbox {
        check(status == CollectorOutboxStatus.PUBLISHING) { "PUBLISHING outbox만 발행 완료할 수 있습니다: $status" }

        return transition(
            status = CollectorOutboxStatus.PUBLISHED,
            lastError = null,
            publishedAt = now,
            claim = null,
            updatedAt = now
        )
    }

    /**
     * 재시도 가능한 실패를 기록한다. 한도에 도달하면 DEAD로 보내 운영 큐에 남긴다.
     */
    fun recordFailure(
        reason: String,
        retryPolicy: CollectorOutboxRetryPolicy,
        now: Instant
    ): CollectorOutbox {
        check(status == CollectorOutboxStatus.PUBLISHING) { "PUBLISHING outbox만 실패를 기록할 수 있습니다: $status" }
        require(reason.isNotBlank()) { "outbox 실패 사유는 비어 있을 수 없습니다" }

        val attempts = retryCount + 1

        if (retryPolicy.exhausted(attempts)) {
            return transition(
                status = CollectorOutboxStatus.DEAD,
                retryCount = attempts,
                lastError = reason,
                claim = null,
                updatedAt = now
            )
        }

        return transition(
            status = CollectorOutboxStatus.PENDING,
            retryCount = attempts,
            nextRetryAt = now.plus(retryPolicy.backoff(attempts)),
            lastError = reason,
            claim = null,
            updatedAt = now
        )
    }

    /**
     * 재시도해도 결과가 달라지지 않는 실패를 한도와 무관하게 DEAD로 보낸다.
     */
    fun markDead(reason: String, now: Instant): CollectorOutbox {
        check(status == CollectorOutboxStatus.PUBLISHING) { "PUBLISHING outbox만 DEAD로 보낼 수 있습니다: $status" }
        require(reason.isNotBlank()) { "outbox 실패 사유는 비어 있을 수 없습니다" }

        return transition(
            status = CollectorOutboxStatus.DEAD,
            lastError = reason,
            claim = null,
            updatedAt = now
        )
    }

    /**
     * 운영자가 원인을 확인한 뒤 DEAD를 다시 발행 대상으로 되돌린다. 자동 복구는 두지 않는다.
     */
    fun recoverToPending(now: Instant): CollectorOutbox {
        check(status == CollectorOutboxStatus.DEAD) { "DEAD outbox만 복구할 수 있습니다: $status" }

        return transition(
            status = CollectorOutboxStatus.PENDING,
            retryCount = 0,
            nextRetryAt = now,
            lastError = null,
            publishedAt = null,
            claim = null,
            updatedAt = now
        )
    }

    /**
     * 전이 결과 인스턴스를 만든다.
     *
     * 전이해도 바뀌지 않는 식별자·이벤트·payload·생성 시각은 인자로 받지 않고, [updatedAt]은 기본값을 두지 않는다.
     * 전이는 항상 갱신 시각을 남긴다.
     */
    private fun transition(
        status: CollectorOutboxStatus = this.status,
        retryCount: Int = this.retryCount,
        nextRetryAt: Instant = this.nextRetryAt,
        lastError: String? = this.lastError,
        publishedAt: Instant? = this.publishedAt,
        claim: CollectorOutboxClaim? = this.claim,
        updatedAt: Instant
    ): CollectorOutbox {
        return CollectorOutbox(
            id = id,
            eventType = eventType,
            eventKey = eventKey,
            partitionKey = partitionKey,
            payload = payload,
            status = status,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
            lastError = lastError,
            publishedAt = publishedAt,
            claim = claim,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
