package me.rgunny.kachi.notification.domain

import me.rgunny.kachi.notification.domain.retry.RetryPolicy
import java.time.Instant


/**
 * 알림 outbox Aggregate Root.
 *
 * DB 트랜잭션과 메시지 broker 발행을 같은 보장 단위로 묶는 패턴.
 * 같은 tx 에서 notification + notification_outbox 동시 commit 후,
 * 폴링으로 broker 에 발행.
 *
 * 처리 권한 획득은 PENDING -> PUBLISHING CAS claim 단계로 분리한다.
 * claim 에 성공한 인스턴스만 publish 를 수행하며, 결과는 callback 에서 별도 트랜잭션으로 반영.
 *
 * PENDING ─▶ PUBLISHING ─┬─▶ PUBLISHED
 *                        ├─▶ PENDING    (실패 시 retry 가능)
 *                        └─▶ DEAD       (재시도 한도 초과 또는 즉시 DEAD)
 * DEAD ─▶ PENDING (운영자 수동 복구)
 */
class NotificationOutbox private constructor(
    val id: NotificationOutboxId,
    val notificationId: NotificationId,
    val topic: String,
    val partitionKey: String,
    val eventPayload: String,
    val createdAt: Instant,
    val outboxStatus: NotificationOutboxStatus,
    val retryCount: Int,
    val nextRetryAt: Instant,
    val lastError: String?,
    val publishedAt: Instant?,
    val claimedAt: Instant?,
    val claimedBy: String?,
) {
    companion object {

        fun create(
            notificationId: NotificationId,
            topic: String,
            partitionKey: String,
            eventPayload: String,
            now: Instant,
        ): NotificationOutbox {
            requireNonBlankForCreate(topic, "topic")
            requireNonBlankForCreate(partitionKey, "partitionKey")
            requireNonBlankForCreate(eventPayload, "eventPayload")

            return NotificationOutbox(
                id = NotificationOutboxId.newId(),
                notificationId = notificationId,
                topic = topic,
                partitionKey = partitionKey,
                eventPayload = eventPayload,
                createdAt = now,
                outboxStatus = NotificationOutboxStatus.PENDING,
                retryCount = 0,
                nextRetryAt = now,
                lastError = null,
                publishedAt = null,
                claimedAt = null,
                claimedBy = null,
            )
        }

        private fun requireNonBlankForCreate(value: String, name: String) {
            require(value.isNotBlank()) { "$name must not be blank" }
        }

        /**
         * 저장소에 보관된 NotificationOutbox aggregate snapshot을 복원한다.
         */
        fun restore(
            id: NotificationOutboxId,
            notificationId: NotificationId,
            topic: String,
            partitionKey: String,
            eventPayload: String,
            createdAt: Instant,
            outboxStatus: NotificationOutboxStatus,
            retryCount: Int,
            nextRetryAt: Instant,
            lastError: String?,
            publishedAt: Instant?,
            claimedAt: Instant?,
            claimedBy: String?,
        ): NotificationOutbox {
            requireNonBlankForCreate(topic, "topic")
            requireNonBlankForCreate(partitionKey, "partitionKey")
            requireNonBlankForCreate(eventPayload, "eventPayload")
            require(retryCount >= 0) { "retryCount must not be negative" }
            require(claimedBy == null || claimedBy.isNotBlank()) { "claimedBy must not be blank" }
            require(lastError == null || lastError.isNotBlank()) { "lastError must not be blank" }
            require((claimedAt == null) == (claimedBy == null)) {
                "claimedAt and claimedBy must be both null or both non-null"
            }
            if (outboxStatus == NotificationOutboxStatus.PUBLISHING) {
                require(claimedAt != null && claimedBy != null) {
                    "PUBLISHING outbox must have claim information"
                }
            } else {
                require(claimedAt == null && claimedBy == null) {
                    "non-PUBLISHING outbox must not have claim information"
                }
            }

            return NotificationOutbox(
                id = id,
                notificationId = notificationId,
                topic = topic,
                partitionKey = partitionKey,
                eventPayload = eventPayload,
                createdAt = createdAt,
                outboxStatus = outboxStatus,
                retryCount = retryCount,
                nextRetryAt = nextRetryAt,
                lastError = lastError,
                publishedAt = publishedAt,
                claimedAt = claimedAt,
                claimedBy = claimedBy,
            )
        }
    }

    /**
     * 처리 권한 획득.
     * NotificationOutboxStatus: [PENDING --> PUBLISHING]
     */
    fun markPublishing(now: Instant, claimedBy: String): NotificationOutbox {
        requireNonBlank(claimedBy, "claimedBy")
        check(outboxStatus == NotificationOutboxStatus.PENDING) {
            "markPublishing requires PENDING, current=$outboxStatus"
        }

        return copy(
            outboxStatus = NotificationOutboxStatus.PUBLISHING,
            claimedAt = now,
            claimedBy = claimedBy,
        )
    }

    /**
     * 발행 성공 처리.
     * NotificationOutboxStatus: [PUBLISHING --> PUBLISHED]
     *
     * consumer 재전달로 같은 발행 결과가 두 번 도착할 수 있어 이미 PUBLISHED면 그대로 둔다.
     */
    fun markPublished(now: Instant): NotificationOutbox {
        if (outboxStatus == NotificationOutboxStatus.PUBLISHED) {
            return this
        }
        check(outboxStatus == NotificationOutboxStatus.PUBLISHING) {
            "markPublished requires PUBLISHING, current=$outboxStatus"
        }

        return copy(
            outboxStatus = NotificationOutboxStatus.PUBLISHED,
            lastError = null,
            publishedAt = now,
            claimedAt = null,
            claimedBy = null,
        )
    }

    /**
     * 발행 실패 처리.
     * NotificationOutboxStatus: [PUBLISHING --> PENDING] 또는 [PUBLISHING --> DEAD]
     */
    fun recordFailure(reason: String, retryPolicy: RetryPolicy, now: Instant): NotificationOutbox {
        requireNonBlank(reason, "reason")
        check(outboxStatus == NotificationOutboxStatus.PUBLISHING) {
            "recordFailure requires PUBLISHING, current=$outboxStatus"
        }

        val nextRetryCount = retryCount + 1

        if (retryPolicy.exhausted(nextRetryCount)) {
            return copy(
                outboxStatus = NotificationOutboxStatus.DEAD,
                retryCount = nextRetryCount,
                lastError = reason,
                claimedAt = null,
                claimedBy = null,
            )
        }

        return copy(
            outboxStatus = NotificationOutboxStatus.PENDING,
            retryCount = nextRetryCount,
            nextRetryAt = now.plus(retryPolicy.backoff(nextRetryCount)),
            lastError = reason,
            claimedAt = null,
            claimedBy = null,
        )
    }

    /**
     * 즉시 DEAD 처리.
     * NotificationOutboxStatus: [PUBLISHING --> DEAD]
     */
    fun markDead(reason: String): NotificationOutbox {
        requireNonBlank(reason, "reason")
        check(outboxStatus == NotificationOutboxStatus.PUBLISHING) {
            "markDead requires PUBLISHING, current=$outboxStatus"
        }

        return copy(
            outboxStatus = NotificationOutboxStatus.DEAD,
            lastError = reason,
            claimedAt = null,
            claimedBy = null,
        )
    }

    /**
     * 운영자 복구.
     * NotificationOutboxStatus: [DEAD --> PENDING]
     */
    fun recoverToPending(now: Instant): NotificationOutbox {
        check(outboxStatus == NotificationOutboxStatus.DEAD) {
            "recoverToPending requires DEAD, current=$outboxStatus"
        }

        return copy(
            outboxStatus = NotificationOutboxStatus.PENDING,
            retryCount = 0,
            nextRetryAt = now,
            lastError = null,
            publishedAt = null,
            claimedAt = null,
            claimedBy = null,
        )
    }

    /**
     * 전이 결과 인스턴스를 만든다.
     *
     * 전이해도 바뀌지 않는 식별자·발행 대상·payload·생성 시각은 인자로 받지 않는다.
     */
    private fun copy(
        outboxStatus: NotificationOutboxStatus = this.outboxStatus,
        retryCount: Int = this.retryCount,
        nextRetryAt: Instant = this.nextRetryAt,
        lastError: String? = this.lastError,
        publishedAt: Instant? = this.publishedAt,
        claimedAt: Instant? = this.claimedAt,
        claimedBy: String? = this.claimedBy,
    ): NotificationOutbox {
        return NotificationOutbox(
            id = id,
            notificationId = notificationId,
            topic = topic,
            partitionKey = partitionKey,
            eventPayload = eventPayload,
            createdAt = createdAt,
            outboxStatus = outboxStatus,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
            lastError = lastError,
            publishedAt = publishedAt,
            claimedAt = claimedAt,
            claimedBy = claimedBy,
        )
    }

    private fun requireNonBlank(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
    }
}
