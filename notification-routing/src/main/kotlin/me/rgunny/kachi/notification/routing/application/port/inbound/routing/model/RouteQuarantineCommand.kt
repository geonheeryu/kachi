package me.rgunny.kachi.notification.routing.application.port.inbound.routing.model

import java.time.Instant

/**
 * story 격리 1건의 라우팅 입력 모델.
 *
 * eventKey는 `{quarantineId}:{quarantinedAt epoch millis}`이고 storyId는 격리된 story의 id다.
 * lastFailureReason은 마지막 실패 사유 코드이며 consecutiveFailures는 0 이상이다.
 */
data class RouteQuarantineCommand(
    val eventKey: String,
    val storyId: String,
    val consecutiveFailures: Int,
    val lastFailureReason: String,
    val quarantinedAt: Instant,
) {
    init {
        require(eventKey.isNotBlank()) { "eventKey must not be blank" }
        require(storyId.isNotBlank()) { "storyId must not be blank" }
        require(consecutiveFailures >= 0) { "consecutiveFailures must not be negative" }
        require(lastFailureReason.isNotBlank()) { "lastFailureReason must not be blank" }
    }
}
