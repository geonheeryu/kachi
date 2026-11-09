package me.rgunny.kachi.ai.domain.quarantine

import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.run.AiRunTargetType
import java.time.Instant

/**
 * 키워드별 연속 실패 누적과 격리 상태.
 *
 * 반복 실패하는 키워드 하나가 window 전체의 진행을 막지 않도록, 그 키워드만 파이프라인에서 걷어낸다.
 * 자세한 결정 배경은 docs/decisions/020-ai-service-scheduler-실행-모델과-요약-window.md 를 참고한다.
 */
class KeywordQuarantine private constructor(
    val id: KeywordQuarantineId,
    val targetType: AiRunTargetType,
    val keyword: AiKeyword,
    val consecutiveFailures: Int,
    val lastFailureReason: AiFailureReason?,
    val status: KeywordQuarantineStatus,
    val quarantinedAt: Instant?,
    val releasedAt: Instant?,
    val updatedAt: Instant
) {
    val isQuarantined: Boolean
        get() = status == KeywordQuarantineStatus.QUARANTINED

    companion object {

        fun track(
            targetType: AiRunTargetType,
            keyword: AiKeyword,
            updatedAt: Instant
        ): KeywordQuarantine {
            return KeywordQuarantine(
                id = KeywordQuarantineId.newId(),
                targetType = targetType,
                keyword = keyword,
                consecutiveFailures = 0,
                lastFailureReason = null,
                status = KeywordQuarantineStatus.TRACKING,
                quarantinedAt = null,
                releasedAt = null,
                updatedAt = updatedAt
            )
        }

        fun restore(
            id: KeywordQuarantineId,
            targetType: AiRunTargetType,
            keyword: AiKeyword,
            consecutiveFailures: Int,
            lastFailureReason: AiFailureReason?,
            status: KeywordQuarantineStatus,
            quarantinedAt: Instant?,
            releasedAt: Instant?,
            updatedAt: Instant
        ): KeywordQuarantine {
            return KeywordQuarantine(
                id = id,
                targetType = targetType,
                keyword = keyword,
                consecutiveFailures = consecutiveFailures,
                lastFailureReason = lastFailureReason,
                status = status,
                quarantinedAt = quarantinedAt,
                releasedAt = releasedAt,
                updatedAt = updatedAt
            )
        }
    }

    /**
     * 실패를 누적하고, 임계치에 도달하면 격리한다.
     */
    fun recordFailure(
        reason: AiFailureReason,
        failureThreshold: Int,
        updatedAt: Instant
    ): KeywordQuarantine {
        require(failureThreshold >= 1) { "격리 임계치는 1 이상이어야 합니다" }

        // 격리된 키워드는 실행 대상에서 이미 빠져 있다. 그래도 들어오면 격리 시점을 덮지 않고 그대로 둔다.
        if (isQuarantined) {
            return this
        }

        val failures = consecutiveFailures + 1
        val quarantined = failures >= failureThreshold

        return KeywordQuarantine(
            id = id,
            targetType = targetType,
            keyword = keyword,
            consecutiveFailures = failures,
            lastFailureReason = reason,
            status = if (quarantined) KeywordQuarantineStatus.QUARANTINED else KeywordQuarantineStatus.TRACKING,
            // quarantinedAt/releasedAt은 현재 상태가 아니라 마지막 격리/해제 시각이다. 현재 상태는 status가 갖는다.
            quarantinedAt = if (quarantined) updatedAt else quarantinedAt,
            releasedAt = releasedAt,
            updatedAt = updatedAt
        )
    }

    /**
     * 연속 실패 누적을 되돌린다. 임계치는 연속 실패에만 반응해야 하므로 성공 한 번으로 0이 된다.
     */
    fun recordSuccess(updatedAt: Instant): KeywordQuarantine {
        if (isQuarantined) {
            return this
        }

        return KeywordQuarantine(
            id = id,
            targetType = targetType,
            keyword = keyword,
            consecutiveFailures = 0,
            lastFailureReason = null,
            status = KeywordQuarantineStatus.TRACKING,
            quarantinedAt = quarantinedAt,
            releasedAt = releasedAt,
            updatedAt = updatedAt
        )
    }

    /**
     * 운영자가 원인을 확인한 뒤 격리를 해제한다. 자동 해제는 두지 않는다.
     */
    fun release(updatedAt: Instant): KeywordQuarantine {
        require(isQuarantined) { "격리된 키워드만 해제할 수 있습니다" }

        return KeywordQuarantine(
            id = id,
            targetType = targetType,
            keyword = keyword,
            consecutiveFailures = 0,
            lastFailureReason = lastFailureReason,
            status = KeywordQuarantineStatus.RELEASED,
            quarantinedAt = quarantinedAt,
            releasedAt = updatedAt,
            updatedAt = updatedAt
        )
    }

    /**
     * 저장이 필요한 상태인지 판단한다. 실패한 적 없는 키워드까지 매 실행 기록하지 않기 위한 조건이다.
     */
    fun needsReset(): Boolean {
        return consecutiveFailures > 0 || status != KeywordQuarantineStatus.TRACKING
    }
}
