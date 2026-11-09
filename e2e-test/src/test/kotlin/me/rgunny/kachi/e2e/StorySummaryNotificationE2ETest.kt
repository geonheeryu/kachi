package me.rgunny.kachi.e2e

import me.rgunny.kachi.ai.contract.AiDevelopmentKind
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.e2e.support.KafkaSupport
import me.rgunny.kachi.e2e.support.NotificationStore
import me.rgunny.kachi.e2e.support.PrometheusMetrics
import me.rgunny.kachi.e2e.support.StoryMarker
import me.rgunny.kachi.e2e.support.TestKeyword
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.contract.NotificationRequestedEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 기사 수집부터 발송 완료까지 story 요약 알림 사이클을 서비스 일곱 개 위에서 돈다.
 *
 * 흐름: user-service에 사용자·바인딩·구독 -> collector가 활성 키워드로 Google stub 수집 -> `collector.news.collected` ->
 * story-service가 TEI stub 임베딩·Qdrant 후보 검색으로 story 조립 -> `story.article.attached` ->
 * ai-service가 미요약 기사 수로 즉시 요약 -> `ai.summary.created`(schemaVersion 2) -> routing -> `notification.requested` ->
 * notification-service outbox -> `notification.dispatch` -> worker가 user-service에서 주소를 받아 mock sender로 발송.
 * 단언의 앵커는 `requestId = sum:{storyId}:{version}:u:{userId}:c:{channel}`(ADR 035)이다.
 */
class StorySummaryNotificationE2ETest : KachiCycleE2ETestBase() {

    @Test
    @DisplayName("수집한 기사가 story로 묶여 요약되면 구독자가 바인딩한 채널 셋 모두에 요약 알림이 발송된다")
    fun deliverStorySummaryToEveryBoundChannel() {
        val marker = StoryMarker.random()
        val keyword = TestKeyword.unique("Nvidia")
        val user = users.registerUser("subscriber")
        users.bindSlack(user)
        users.bindDiscord(user)
        users.bindTelegram(user)
        users.subscribe(user, keyword.displayName, setOf(SLACK, DISCORD, TELEGRAM))
        cluster.llm.respondSummary(marker, title = "NVIDIA 실적 요약", content = "실적 호조")
        publishArticles(marker, listOf(keyword), count = MIN_NEW_ARTICLES)
        val resolvedBefore = resolvedFromUserService()

        assertEquals(MIN_NEW_ARTICLES, collectNews())

        // A. story-service가 기사 셋을 story 하나로 묶는다.
        val storyId = awaitStoryOf(keyword, MIN_NEW_ARTICLES)

        // B. ai-service가 그 story의 첫 요약 버전을 schemaVersion 2 계약으로 발행한다.
        val summary = readSummary(awaitSummaryRecordOf(storyId))
        assertEquals(AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION, summary.schemaVersion)
        assertEquals(storyId, summary.storyId)
        assertEquals(1L, summary.version)
        assertEquals(listOf(keyword.canonicalKey), summary.keywords)
        assertEquals(AiDevelopmentKind.DEVELOPMENT, summary.developmentKind)
        assertEquals(MIN_NEW_ARTICLES, summary.sourceNewsCount)

        // C. routing이 수신자 x 채널만큼 requestId를 key로 접수 이벤트를 발행한다.
        val requestIds = listOf(SLACK, DISCORD, TELEGRAM).map { summaryRequestId(summary, user.userId, it) }.toSet()
        val requested = kafka.recordsOf(KafkaSupport.TOPIC_NOTIFICATION_REQUESTED, expected = 3, timeout = PIPELINE_TIMEOUT) {
            it.key() in requestIds
        }
        assertEquals(requestIds, requested.map { it.key() }.toSet())
        requested.forEach { record ->
            val event = http.jsonMapper.readValue(record.value(), NotificationRequestedEvent::class.java)
            val origin = event.origin!!
            assertEquals(user.userId, event.recipientId)
            assertEquals(summary.summaryId, origin.summaryId)
            assertEquals(storyId, origin.storyId)
            assertEquals(summary.version, origin.version)
            assertEquals(listOf(keyword.canonicalKey), origin.keywords)
            assertEquals(user.userId, origin.userId)
            assertTrue(event.message.startsWith("[${keyword.displayName}] NVIDIA 실적 요약"), event.message)
        }

        // D. worker가 주소를 받아 세 건 모두 발송을 끝낸다.
        requestIds.forEach { notifications.awaitStatus(it, NotificationStore.STATUS_SENT) }

        // E. 발송 직전 가드와 주소 캐시가 Redis에 남는다.
        requestIds.forEach { assertEquals(listOf("notification:sent:$it"), cluster.infrastructure.redisKeys("notification:sent:$it")) }
        assertEquals(3, cluster.infrastructure.redisKeys("notification:recipient:${user.userId}:*").size)

        // F. 주소는 user-service에서 세 번 받았다.
        assertEquals(3.0, resolvedFromUserService() - resolvedBefore)
    }

    @Test
    @DisplayName("한 사용자가 story의 키워드 둘을 같은 채널로 구독해도 story 한 버전에 알림은 한 통이고 머리에 두 키워드가 붙는다")
    fun deliverOneNotificationPerRecipientAndStoryVersion() {
        val marker = StoryMarker.random()
        val zeta = TestKeyword.unique("Zeta")
        val alpha = TestKeyword.unique("Alpha")
        val user = users.registerUser("multi")
        users.bindSlack(user)
        users.subscribe(user, zeta.displayName, setOf(SLACK))
        users.subscribe(user, alpha.displayName, setOf(SLACK))
        cluster.llm.respondSummary(marker, title = "두 키워드 요약")
        publishArticles(marker, listOf(zeta, alpha), count = MIN_NEW_ARTICLES)

        assertEquals(MIN_NEW_ARTICLES, collectNews())

        val storyId = awaitStoryOf(alpha, MIN_NEW_ARTICLES)
        val summary = readSummary(awaitSummaryRecordOf(storyId))
        assertEquals(setOf(alpha.canonicalKey, zeta.canonicalKey), summary.keywords.toSet())

        val requestId = summaryRequestId(summary, user.userId, SLACK)
        notifications.awaitStatus(requestId, NotificationStore.STATUS_SENT)

        // 이 사용자에게 간 이 story의 접수 이벤트는 키워드 수와 무관하게 한 건이다.
        val requested = kafka.recordsOf(KafkaSupport.TOPIC_NOTIFICATION_REQUESTED, expected = 2, timeout = Duration.ofSeconds(10)) {
            it.key().startsWith("sum:$storyId:") && it.key().contains(":u:${user.userId}:")
        }
        assertEquals(listOf(requestId), requested.map { it.key() })
        assertEquals(1, notifications.findByRecipient(user.userId, "sum:$storyId:").size)

        val event = http.jsonMapper.readValue(requested.single().value(), NotificationRequestedEvent::class.java)
        assertTrue(event.message.startsWith("[${alpha.displayName}, ${zeta.displayName}] 두 키워드 요약"), event.message)
        assertEquals(setOf(alpha.canonicalKey, zeta.canonicalKey), event.origin!!.keywords.toSet())
    }

    @Test
    @DisplayName("같은 요약 이벤트가 다시 전달돼도 접수 이벤트는 늘지 않는다")
    fun ignoreRedeliveredSummaryEvent() {
        val marker = StoryMarker.random()
        val keyword = TestKeyword.unique("Redeliver")
        val user = users.registerUser("redeliver")
        users.bindSlack(user)
        users.subscribe(user, keyword.displayName, setOf(SLACK))
        cluster.llm.respondSummary(marker)
        publishArticles(marker, listOf(keyword), count = MIN_NEW_ARTICLES)
        assertEquals(MIN_NEW_ARTICLES, collectNews())

        val storyId = awaitStoryOf(keyword, MIN_NEW_ARTICLES)
        val summaryRecord = awaitSummaryRecordOf(storyId)
        val requestId = summaryRequestId(readSummary(summaryRecord), user.userId, SLACK)
        notifications.awaitStatus(requestId, NotificationStore.STATUS_SENT)

        // 같은 레코드를 다시 넣는다. routing의 RoutingJob(eventKey = storyId:version)이 두 번째 fan-out을 막는다.
        kafka.send(KafkaSupport.TOPIC_SUMMARY_CREATED, summaryRecord.key(), summaryRecord.value())

        val requested = kafka.recordsOf(KafkaSupport.TOPIC_NOTIFICATION_REQUESTED, expected = 2, timeout = Duration.ofSeconds(10)) {
            it.key() == requestId
        }
        assertEquals(1, requested.size)
        assertEquals(1, notifications.countByRequestIdPrefix("sum:$storyId:"))
    }

    @Test
    @DisplayName("접수 뒤에 바인딩이 없어진 수신자의 알림은 보내지 않고 스킵으로 끝낸다")
    fun suppressRecipientWithoutBinding() {
        // 구독 수신자 조회는 ACTIVE 바인딩이 있는 채널만 돌려주므로 routing은 이 경우를 만들지 않는다.
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

    private fun resolvedFromUserService(): Double {
        return workerMetrics.counter(
            PrometheusMetrics.RECIPIENT_RESOLVE_TOTAL,
            mapOf("result" to "available", "source" to "user_service")
        )
    }
}
