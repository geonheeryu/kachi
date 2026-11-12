package me.rgunny.kachi.notification.routing.application.service

import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.SummarySentiment
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedKeyword
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("NotificationMessageRenderer")
class NotificationMessageRendererTest {

    private val nvidia = SubscribedKeyword("nvidia", "NVIDIA")
    private val tsmc = SubscribedKeyword("tsmc", "TSMC")

    @Test
    @DisplayName("요약 본문은 구독 키워드·제목·내용·감성·기사 수·시각 순이다")
    fun renderSummary() {
        val message = NotificationMessageRenderer.renderSummary(summaryCommand(), listOf(nvidia, tsmc))

        assertEquals(
            "[NVIDIA, TSMC] NVIDIA and TSMC expand partnership\n\nNVIDIA and TSMC announced a new plant.\n\n감성 POSITIVE · 기사 3건 · 2026-06-13T00:00:00Z",
            message,
        )
    }

    @Test
    @DisplayName("머리의 displayName은 받은 순서가 아니라 canonicalKey 순이다")
    fun sortByCanonicalKey() {
        val message = NotificationMessageRenderer.renderSummary(
            summaryCommand(),
            listOf(SubscribedKeyword("zeta", "Alpha Corp"), SubscribedKeyword("alpha", "Zeta Inc")),
        )

        assertTrue(message.startsWith("[Zeta Inc, Alpha Corp] "))
    }

    @Test
    @DisplayName("머리는 수신자가 구독한 키워드만 담는다")
    fun headerHasOnlySubscribedKeywords() {
        val message = NotificationMessageRenderer.renderSummary(summaryCommand(), listOf(tsmc))

        assertTrue(message.startsWith("[TSMC] NVIDIA and TSMC expand partnership"))
    }

    @Test
    @DisplayName("격리 본문은 격리 표시와 storyId·실패 횟수·사유·시각을 담는다")
    fun renderQuarantine() {
        val message = NotificationMessageRenderer.renderQuarantine(
            RouteQuarantineCommand(
                eventKey = "quarantine-1:1",
                storyId = "story-1",
                consecutiveFailures = 3,
                lastFailureReason = "INVALID_RESPONSE",
                quarantinedAt = NOW,
            )
        )

        assertEquals("[story 격리] 'story-1' — 연속 실패 3회, 사유 INVALID_RESPONSE, 2026-06-13T00:00:00Z", message)
    }

    @Test
    @DisplayName("요약 본문이 2000자를 넘으면 잘라내고 끝에 말줄임을 붙인다")
    fun truncateSummary() {
        val message = NotificationMessageRenderer.renderSummary(summaryCommand(content = "x".repeat(3000)), listOf(nvidia))

        assertEquals(NotificationMessageRenderer.MAX_LENGTH, message.length)
        assertTrue(message.endsWith("…"))
    }

    @Test
    @DisplayName("격리 본문도 같은 길이 제한으로 잘라낸다")
    fun truncateQuarantine() {
        val message = NotificationMessageRenderer.renderQuarantine(
            RouteQuarantineCommand(
                eventKey = "quarantine-1:1",
                storyId = "s".repeat(3000),
                consecutiveFailures = 3,
                lastFailureReason = "INVALID_RESPONSE",
                quarantinedAt = NOW,
            )
        )

        assertEquals(NotificationMessageRenderer.MAX_LENGTH, message.length)
        assertTrue(message.endsWith("…"))
    }

    @Test
    @DisplayName("2000자 이하는 그대로 둔다")
    fun keepWithinLimit() {
        val prefixLength = NotificationMessageRenderer.renderSummary(summaryCommand(content = ""), listOf(nvidia)).length
        val message = NotificationMessageRenderer.renderSummary(
            summaryCommand(content = "x".repeat(NotificationMessageRenderer.MAX_LENGTH - prefixLength)),
            listOf(nvidia),
        )

        assertEquals(NotificationMessageRenderer.MAX_LENGTH, message.length)
        assertTrue(message.endsWith("2026-06-13T00:00:00Z"))
    }

    private fun summaryCommand(content: String = "NVIDIA and TSMC announced a new plant."): RouteSummaryCommand {
        return RouteSummaryCommand(
            summaryId = "summary-1",
            storyId = "story-1",
            version = 1,
            keywords = listOf("nvidia", "tsmc"),
            title = "NVIDIA and TSMC expand partnership",
            content = content,
            sentiment = SummarySentiment.POSITIVE,
            sourceNewsCount = 3,
            createdAt = NOW,
        )
    }
}
