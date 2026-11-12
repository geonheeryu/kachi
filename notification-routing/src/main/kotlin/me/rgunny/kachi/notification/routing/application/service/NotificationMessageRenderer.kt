package me.rgunny.kachi.notification.routing.application.service

import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedKeyword
import org.slf4j.LoggerFactory

/**
 * 라우팅 입력을 사람이 읽을 알림 본문으로 옮기는 renderer.
 *
 * 채널별 포맷은 없다.
 * 본문이 [MAX_LENGTH]를 넘으면 끝을 잘라 [MAX_LENGTH]자로 맞추고 경고를 남긴다.
 */
object NotificationMessageRenderer {

    const val MAX_LENGTH = 2000
    private const val ELLIPSIS = "…"
    private val log = LoggerFactory.getLogger(NotificationMessageRenderer::class.java)

    /**
     * 수신자 한 명의 요약 본문을 만든다.
     *
     * 머리에는 그 수신자가 구독한 키워드의 displayName을 canonicalKey 순으로 ", "로 이어 붙인다.
     */
    fun renderSummary(command: RouteSummaryCommand, subscribedKeywords: List<SubscribedKeyword>): String {
        val message = buildString {
            append('[')
            append(subscribedKeywords.sortedBy { it.canonicalKey }.joinToString(", ") { it.displayName })
            append("] ").append(command.title)
            append("\n\n").append(command.content)
            append("\n\n감성 ").append(command.sentiment.name)
            append(" · 기사 ").append(command.sourceNewsCount).append("건")
            append(" · ").append(command.createdAt)
        }
        return truncate(message, "summaryId=${command.summaryId}")
    }

    fun renderQuarantine(command: RouteQuarantineCommand): String {
        val message = buildString {
            append("[story 격리] '").append(command.storyId).append("'")
            append(" — 연속 실패 ").append(command.consecutiveFailures).append("회")
            append(", 사유 ").append(command.lastFailureReason)
            append(", ").append(command.quarantinedAt)
        }
        return truncate(message, "eventKey=${command.eventKey}")
    }

    private fun truncate(message: String, source: String): String {
        if (message.length <= MAX_LENGTH) {
            return message
        }
        log.warn("notification message truncated {} length={} max={}", source, message.length, MAX_LENGTH)
        return message.take(MAX_LENGTH - ELLIPSIS.length) + ELLIPSIS
    }
}
