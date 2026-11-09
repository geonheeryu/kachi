package me.rgunny.kachi.e2e

import me.rgunny.kachi.ai.contract.AiSummaryCreatedEventV2
import me.rgunny.kachi.e2e.support.JsonHttp
import me.rgunny.kachi.e2e.support.JwtSupport
import me.rgunny.kachi.e2e.support.KachiCluster
import me.rgunny.kachi.e2e.support.KafkaSupport
import me.rgunny.kachi.e2e.support.NotificationStore
import me.rgunny.kachi.e2e.support.PrometheusMetrics
import me.rgunny.kachi.e2e.support.ServiceLogsOnFailure
import me.rgunny.kachi.e2e.support.StoryMarker
import me.rgunny.kachi.e2e.support.TestKeyword
import me.rgunny.kachi.e2e.support.TestNewsArticle
import me.rgunny.kachi.e2e.support.UserServiceClient
import me.rgunny.kachi.story.contract.StoryArticleAttachedEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * e2e 시나리오 클래스의 공통 뼈대.
 *
 * 모든 클래스가 [KachiCluster.shared] 하나를 쓴다.
 * 테스트 메서드는 서로 순서에 기대지 않는다. 각자 고유한 사용자·키워드·[StoryMarker]를 만들고 storyId와 requestId로 자기 결과만 찾는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(ServiceLogsOnFailure::class)
abstract class KachiCycleE2ETestBase {
    protected val http = JsonHttp()
    protected lateinit var cluster: KachiCluster
    protected lateinit var users: UserServiceClient
    protected lateinit var kafka: KafkaSupport
    protected lateinit var notifications: NotificationStore
    protected lateinit var workerMetrics: PrometheusMetrics

    @BeforeAll
    fun connectCluster() {
        cluster = KachiCluster.shared()
        users = UserServiceClient(cluster.userService.baseUrl, http, JwtSupport(cluster.jwtSecret))
        kafka = KafkaSupport(cluster.infrastructure.kafka.bootstrapServers)
        notifications = NotificationStore(
            cluster.infrastructure.mongoUriFromHost(KachiCluster.NOTIFICATION_DATABASE),
            KachiCluster.NOTIFICATION_DATABASE
        )
        workerMetrics = PrometheusMetrics(http, cluster.worker.baseUrl)
    }

    @AfterAll
    fun closeClients() {
        if (::notifications.isInitialized) notifications.close()
    }

    /**
     * [marker] story의 기사 [count]건을 Google News stub의 [keywords] 검색 결과마다 둔다.
     *
     * 키워드가 여럿이면 같은 기사(같은 URL)가 각 검색에 나오고, collector는 그 기사의 matchedKeywords를 합친다.
     */
    protected fun publishArticles(marker: StoryMarker, keywords: List<TestKeyword>, count: Int): List<TestNewsArticle> {
        val now = Instant.now()
        val articles = List(count) { index ->
            TestNewsArticle(
                title = "${marker.value} e2e 기사 ${index + 1}",
                url = "https://news.example.com/${marker.value}/${index + 1}",
                publisher = "e2e-press",
                publishedAt = now.minus(Duration.ofMinutes(count - index.toLong()))
            )
        }
        keywords.forEach { cluster.news.publish(it.canonicalKey, articles) }
        return articles
    }

    /**
     * collector-service 수집 API로 활성 키워드 전체를 Google에서 한 번 수집한다.
     *
     * 수집은 동기라 돌아오면 기사와 outbox 행이 저장돼 있다. 새로 저장된 기사 수를 돌려준다.
     */
    protected fun collectNews(): Int {
        val response = http.post(
            "${cluster.collector.baseUrl}/api/v1/internal/collections/news",
            mapOf("sources" to listOf("GOOGLE"))
        ).expect(200)
        return response.data.path("collectedCount").asInt()
    }

    /**
     * `story.article.attached`에서 [keyword]가 붙은 기사 [articleCount]건을 기다린다.
     *
     * 모두 한 story에 붙었는지 단언하고 그 storyId를 돌려준다.
     */
    protected fun awaitStoryOf(keyword: TestKeyword, articleCount: Int): String {
        val events = kafka.recordsOf(KafkaSupport.TOPIC_STORY_ARTICLE_ATTACHED, expected = articleCount, timeout = PIPELINE_TIMEOUT) {
            keyword.canonicalKey in readAttached(it).storyKeywords
        }.map(::readAttached)
        assertEquals(articleCount, events.size, "story.article.attached for ${keyword.canonicalKey}")
        val storyIds = events.map { it.storyId }.toSet()
        assertEquals(1, storyIds.size, "기사가 story 하나에 붙어야 한다: $storyIds")
        return storyIds.single()
    }

    /** [storyId]의 `ai.summary.created` 레코드 하나를 기다린다. key는 storyId다. */
    protected fun awaitSummaryRecordOf(storyId: String): ConsumerRecord<String, String> {
        val records = kafka.recordsOf(KafkaSupport.TOPIC_SUMMARY_CREATED, expected = 1, timeout = PIPELINE_TIMEOUT) {
            it.key() == storyId
        }
        assertEquals(1, records.size, "ai.summary.created for story $storyId")
        return records.single()
    }

    protected fun readSummary(record: ConsumerRecord<String, String>): AiSummaryCreatedEventV2 {
        return http.jsonMapper.readValue(record.value(), AiSummaryCreatedEventV2::class.java)
    }

    protected fun summaryRequestId(summary: AiSummaryCreatedEventV2, userId: String, channel: String): String {
        return "sum:${summary.storyId}:${summary.version}:u:$userId:c:$channel"
    }

    private fun readAttached(record: ConsumerRecord<String, String>): StoryArticleAttachedEvent {
        return http.jsonMapper.readValue(record.value(), StoryArticleAttachedEvent::class.java)
    }

    protected companion object {
        const val SLACK = "SLACK"
        const val DISCORD = "DISCORD"
        const val TELEGRAM = "TELEGRAM"

        // ai-service `kachi.ai.story-summary.min-new-articles` 기본값(덮어쓰지 않음)
        const val MIN_NEW_ARTICLES = 3

        // 수집에서 요약 이벤트까지(첫 테스트의 consumer group 합류 포함)
        val PIPELINE_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
