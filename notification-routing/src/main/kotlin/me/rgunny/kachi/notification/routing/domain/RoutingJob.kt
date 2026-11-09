package me.rgunny.kachi.notification.routing.domain

import java.time.Instant

/**
 * 이벤트 1건을 라우팅한 기록 Aggregate Root.
 *
 * eventKey가 유일하므로 같은 이벤트를 두 번 받아도 job은 하나다.
 * storyId는 요약이면 요약된 story, 격리면 격리된 story의 id다.
 * COMPLETED면 다시 라우팅하지 않고, STARTED면 cursor부터 이어서 발행한다.
 * cursor는 다음에 읽을 수신자 페이지의 위치이고, STARTED에서 null이면 첫 페이지부터, COMPLETED에서는 항상 null이다.
 * targetCount와 publishedCount는 끝낸 페이지들의 누계이고, publishedCount는 접수 topic으로 발행한 건수다.
 */
class RoutingJob private constructor(
    val id: RoutingJobId,
    val eventKey: String,
    val kind: RoutingJobKind,
    val storyId: String,
    val status: RoutingJobStatus,
    val cursor: String?,
    val targetCount: Int,
    val publishedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
) {
    companion object {

        fun start(
            eventKey: String,
            kind: RoutingJobKind,
            storyId: String,
            now: Instant,
        ): RoutingJob {
            require(eventKey.isNotBlank()) { "eventKey must not be blank" }
            require(storyId.isNotBlank()) { "storyId must not be blank" }

            return RoutingJob(
                id = RoutingJobId.newId(),
                eventKey = eventKey,
                kind = kind,
                storyId = storyId,
                status = RoutingJobStatus.STARTED,
                cursor = null,
                targetCount = 0,
                publishedCount = 0,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
            )
        }

        fun restore(
            id: RoutingJobId,
            eventKey: String,
            kind: RoutingJobKind,
            storyId: String,
            status: RoutingJobStatus,
            cursor: String?,
            targetCount: Int,
            publishedCount: Int,
            createdAt: Instant,
            updatedAt: Instant,
            completedAt: Instant?,
        ): RoutingJob {
            require(eventKey.isNotBlank()) { "eventKey must not be blank" }
            require(storyId.isNotBlank()) { "storyId must not be blank" }
            require(cursor == null || cursor.isNotBlank()) { "cursor must not be blank" }
            requireCounts(targetCount, publishedCount)
            require((status == RoutingJobStatus.COMPLETED) == (completedAt != null)) {
                "completedAt must be present only for COMPLETED job"
            }
            require(status == RoutingJobStatus.STARTED || cursor == null) {
                "cursor must be null for COMPLETED job"
            }

            return RoutingJob(
                id = id,
                eventKey = eventKey,
                kind = kind,
                storyId = storyId,
                status = status,
                cursor = cursor,
                targetCount = targetCount,
                publishedCount = publishedCount,
                createdAt = createdAt,
                updatedAt = updatedAt,
                completedAt = completedAt,
            )
        }

        private fun requireCounts(targetCount: Int, publishedCount: Int) {
            require(targetCount >= 0) { "targetCount must not be negative" }
            require(publishedCount >= 0) { "publishedCount must not be negative" }
            require(publishedCount <= targetCount) { "publishedCount must not exceed targetCount" }
        }
    }

    /**
     * 한 페이지를 끝내고 다음 페이지 위치로 옮긴다.
     * RoutingJobStatus: [STARTED --> STARTED]
     *
     * cursor는 non-blank이고, delta는 0 이상이며 publishedDelta는 targetDelta 이하다.
     */
    fun advance(
        cursor: String,
        targetDelta: Int,
        publishedDelta: Int,
        now: Instant,
    ): RoutingJob {
        require(cursor.isNotBlank()) { "cursor must not be blank" }
        requireCounts(targetDelta, publishedDelta)
        check(status == RoutingJobStatus.STARTED) {
            "advance requires STARTED, current=$status"
        }

        return copy(
            status = RoutingJobStatus.STARTED,
            cursor = cursor,
            targetCount = targetCount + targetDelta,
            publishedCount = publishedCount + publishedDelta,
            updatedAt = now,
            completedAt = null,
        )
    }

    /**
     * 마지막 페이지의 delta를 더하고 라우팅을 끝낸다.
     * RoutingJobStatus: [STARTED --> COMPLETED]
     *
     * delta는 0 이상이며 publishedDelta는 targetDelta 이하다.
     * 완료된 job의 cursor는 null이다.
     */
    fun complete(
        targetDelta: Int,
        publishedDelta: Int,
        now: Instant,
    ): RoutingJob {
        requireCounts(targetDelta, publishedDelta)
        check(status == RoutingJobStatus.STARTED) {
            "complete requires STARTED, current=$status"
        }

        return copy(
            status = RoutingJobStatus.COMPLETED,
            cursor = null,
            targetCount = targetCount + targetDelta,
            publishedCount = publishedCount + publishedDelta,
            updatedAt = now,
            completedAt = now,
        )
    }

    private fun copy(
        status: RoutingJobStatus,
        cursor: String?,
        targetCount: Int,
        publishedCount: Int,
        updatedAt: Instant,
        completedAt: Instant?,
    ): RoutingJob {
        return RoutingJob(
            id = id,
            eventKey = eventKey,
            kind = kind,
            storyId = storyId,
            status = status,
            cursor = cursor,
            targetCount = targetCount,
            publishedCount = publishedCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = completedAt,
        )
    }
}
