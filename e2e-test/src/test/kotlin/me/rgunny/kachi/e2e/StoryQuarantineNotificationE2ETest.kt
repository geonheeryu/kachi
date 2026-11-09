package me.rgunny.kachi.e2e

import me.rgunny.kachi.ai.contract.AiFailureReason
import me.rgunny.kachi.ai.contract.AiStoryQuarantinedEvent
import me.rgunny.kachi.e2e.support.KachiCluster
import me.rgunny.kachi.e2e.support.KafkaSupport
import me.rgunny.kachi.e2e.support.StoryMarker
import me.rgunny.kachi.e2e.support.TestKeyword
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * story 격리 알림이 관리자에게 가는 사이클을 서비스 일곱 개 위에서 돈다.
 *
 * LLM stub이 이 story의 요약 요청을 400으로 거절한다. ai-service는 이것을 story 탓 실패로 세고,
 * 즉시 트리거가 임계치만큼 연속 실패하면 `ai.story.quarantined`를 발행한다.
 * routing은 user-service의 관리자 목록(`/internal/users?role=ADMIN`)으로 fan-out한다.
 * 관리자는 local seed가 만든 계정이고 채널 바인딩 셋을 갖는다.
 */
class StoryQuarantineNotificationE2ETest : KachiCycleE2ETestBase() {

    @Test
    @DisplayName("story 요약이 임계치만큼 연속 실패해 격리되면 관리자가 바인딩한 채널마다 격리 알림이 발송된다")
    fun deliverStoryQuarantineToAdmins() {
        val marker = StoryMarker.random()
        val keyword = TestKeyword.unique("Broken")
        val user = users.registerUser("broken")
        users.bindSlack(user)
        users.subscribe(user, keyword.displayName, setOf(SLACK))
        val admins = users.admins()
        assertTrue(admins.isNotEmpty(), "local seed 관리자가 있어야 한다")
        cluster.llm.rejectRequests(marker)
        // 미요약 기사가 MIN_NEW_ARTICLES에 닿은 뒤로 기사 하나마다 즉시 트리거가 한 번 돈다.
        val articleCount = MIN_NEW_ARTICLES + KachiCluster.QUARANTINE_FAILURE_THRESHOLD - 1
        publishArticles(marker, listOf(keyword), count = articleCount)

        assertEquals(articleCount, collectNews())

        val storyId = awaitStoryOf(keyword, articleCount)
        val records = kafka.recordsOf(KafkaSupport.TOPIC_STORY_QUARANTINED, expected = 1, timeout = PIPELINE_TIMEOUT) {
            it.key() == storyId
        }
        assertEquals(1, records.size, "ai.story.quarantined for story $storyId")
        val event = http.jsonMapper.readValue(records.single().value(), AiStoryQuarantinedEvent::class.java)
        assertEquals(storyId, event.storyId)
        assertEquals(KachiCluster.QUARANTINE_FAILURE_THRESHOLD, event.consecutiveFailures)
        assertEquals(AiFailureReason.CLIENT_ERROR, event.lastFailureReason)
        assertEquals(KachiCluster.QUARANTINE_FAILURE_THRESHOLD, cluster.llm.requestsFor(marker).size)

        val eventKey = "${event.quarantineId}:${event.quarantinedAt.toEpochMilli()}"
        admins.forEach { (adminId, channels) ->
            val sent = notifications.awaitSentChannels(adminId, requestIdPrefix = "qrt:$eventKey:u:$adminId:c:", expectedCount = channels.size)
            assertEquals(channels.toSet(), sent, "admin $adminId")
        }
        assertEquals(0, notifications.countByRequestIdPrefix("sum:$storyId:"))
    }
}
