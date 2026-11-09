package me.rgunny.kachi.e2e

import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.e2e.support.KafkaSupport
import me.rgunny.kachi.e2e.support.NotificationStore
import me.rgunny.kachi.e2e.support.PrometheusMetrics
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.contract.NotificationRequestedEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 구독 등록부터 발송 완료까지 요약 알림 한 사이클을 실제 서비스 다섯 개 위에서 돈다.
 *
 * 흐름: user-service에 사용자·바인딩·구독 -> collector stub의 뉴스 -> ai-service 요약 -> `ai.summary.created` ->
 * routing -> `notification.requested` -> notification-service outbox -> `notification.dispatch` -> worker가 user-service에서
 * 주소를 받아 mock sender로 발송. 단언의 앵커는 `requestId = sum:{summaryId}:u:{userId}:c:{channel}`(ADR 025)이다.
 */
class SummaryNotificationCycleE2ETest : KachiCycleE2ETestBase() {

    @Test
    @DisplayName("구독자가 바인딩한 채널 셋 모두에 요약 알림이 발송된다")
    fun deliverSummaryToEveryBoundChannel() {
        val keyword = uniqueKeyword("nvidia")
        val user = users.registerUser("subscriber")
        users.bindSlack(user)
        users.bindDiscord(user)
        users.bindTelegram(user)
        users.subscribe(user, keyword, setOf(SLACK, DISCORD, TELEGRAM))
        stubNews(count = 3)
        cluster.llm.respondSummary(title = "NVIDIA 요약", content = "실적 호조")
        val resolvedBefore = resolvedFromUserService()

        summarize(keyword)

        // A. ai-service가 계약 형태로 요약 이벤트를 발행한다.
        val summaryRecord = kafka.recordsOf(KafkaSupport.TOPIC_SUMMARY_CREATED, expected = 1) { it.key() == keyword }.single()
        val summary = http.jsonMapper.readValue(summaryRecord.value(), AiSummaryCreatedEvent::class.java)
        assertEquals(AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION, summary.schemaVersion)
        assertEquals(3, summary.sourceNewsCount)

        // B. routing이 사용자 x 채널만큼 requestId를 key로 접수 이벤트를 발행한다.
        val requestIds = listOf(SLACK, DISCORD, TELEGRAM).map { summaryRequestId(summary.summaryId, user.userId, it) }.toSet()
        val requested = kafka.recordsOf(KafkaSupport.TOPIC_NOTIFICATION_REQUESTED, expected = 3) { it.key() in requestIds }
        assertEquals(requestIds, requested.map { it.key() }.toSet())
        requested.forEach { record ->
            val event = http.jsonMapper.readValue(record.value(), NotificationRequestedEvent::class.java)
            assertEquals(user.userId, event.recipientId)
            assertEquals(summary.summaryId, event.origin?.summaryId)
            assertTrue(event.message.contains("NVIDIA 요약"))
        }

        // C. worker가 주소를 받아 세 건 모두 발송을 끝낸다.
        requestIds.forEach { notifications.awaitStatus(it, NotificationStore.STATUS_SENT) }

        // D. 발송 직전 가드와 주소 캐시가 Redis에 남는다.
        requestIds.forEach { assertEquals(listOf("notification:sent:$it"), cluster.infrastructure.redisKeys("notification:sent:$it")) }
        assertEquals(3, cluster.infrastructure.redisKeys("notification:recipient:${user.userId}:*").size)

        // E. 주소는 user-service에서 세 번 받았다.
        assertEquals(3.0, resolvedFromUserService() - resolvedBefore)
    }

    @Test
    @DisplayName("접수 뒤에 바인딩이 없어진 수신자의 알림은 보내지 않고 스킵으로 끝낸다")
    fun suppressRecipientWithoutBinding() {
        // 구독 조회는 ACTIVE 바인딩이 있는 채널만 돌려주므로 routing은 이 경우를 만들지 않는다.
        // 접수와 발송 사이에 바인딩이 해지된 상황을 재현하려면 접수 이벤트를 외부 producer처럼 직접 넣는다(계약이 허용하는 경로).
        val user = users.registerUser("unbound")
        val requestId = "e2e:${UUID.randomUUID()}:u:${user.userId}:c:$DISCORD"
        val event = NotificationRequestedEvent(
            requestId = requestId,
            requester = "e2e-test",
            channel = NotificationChannel.DISCORD,
            recipientId = user.userId,
            message = "binding revoked after request"
        )

        kafka.send(KafkaSupport.TOPIC_NOTIFICATION_REQUESTED, requestId, http.jsonMapper.writeValueAsString(event))

        assertEquals(NotificationStore.STATUS_SUPPRESSED, notifications.awaitStatus(requestId, NotificationStore.STATUS_SUPPRESSED))
        assertTrue(cluster.infrastructure.redisKeys("notification:sent:$requestId").isEmpty())
        // 없음도 캐시된다(ADR 028).
        assertEquals(1, cluster.infrastructure.redisKeys("notification:recipient:${user.userId}:$DISCORD").size)
    }

    @Test
    @DisplayName("같은 요약 이벤트가 다시 전달돼도 접수 이벤트는 늘지 않는다")
    fun ignoreRedeliveredSummaryEvent() {
        val keyword = uniqueKeyword("redeliver")
        val user = users.registerUser("redeliver")
        users.bindSlack(user)
        users.subscribe(user, keyword, setOf(SLACK))
        stubNews(count = 1)
        cluster.llm.respondSummary()

        summarize(keyword)

        val summaryRecord = kafka.recordsOf(KafkaSupport.TOPIC_SUMMARY_CREATED, expected = 1) { it.key() == keyword }.single()
        val summaryId = http.jsonMapper.readValue(summaryRecord.value(), AiSummaryCreatedEvent::class.java).summaryId
        val requestId = summaryRequestId(summaryId, user.userId, SLACK)
        notifications.awaitStatus(requestId, NotificationStore.STATUS_SENT)

        // 같은 레코드를 다시 넣는다. routing의 RoutingJob unique가 두 번째 fan-out을 막는다.
        kafka.send(KafkaSupport.TOPIC_SUMMARY_CREATED, summaryRecord.key(), summaryRecord.value())

        val requested = kafka.recordsOf(KafkaSupport.TOPIC_NOTIFICATION_REQUESTED, expected = 2, timeout = Duration.ofSeconds(10)) {
            it.key() == requestId
        }
        assertEquals(1, requested.size)
        assertEquals(1, notifications.countByRequestIdPrefix("sum:$summaryId:"))
    }

    private fun resolvedFromUserService(): Double {
        return workerMetrics.counter(
            PrometheusMetrics.RECIPIENT_RESOLVE_TOTAL,
            mapOf("result" to "available", "source" to "user_service")
        )
    }
}
