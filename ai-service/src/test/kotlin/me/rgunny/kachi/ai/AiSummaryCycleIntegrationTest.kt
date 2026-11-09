package me.rgunny.kachi.ai

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.adapter.outbound.persistence.outbox.AiOutboxMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.quarantine.KeywordQuarantineMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.run.AiRunMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.summary.NewsSummaryMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.watermark.SummaryWatermarkMongoDocument
import me.rgunny.kachi.ai.application.port.inbound.news.SummarizeNewsUseCase
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizeNewsCommand
import me.rgunny.kachi.ai.application.port.inbound.news.model.WatermarkSummaryWindowRequest
import me.rgunny.kachi.ai.application.port.inbound.outbox.RelayAiOutboxUseCase
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderAdminPort
import me.rgunny.kachi.ai.contract.AiKeywordQuarantinedEvent
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.ai.contract.AiTargetType
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.outbox.AiOutboxClaim
import me.rgunny.kachi.ai.domain.outbox.AiOutboxStatus
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.AiOutboxCollection
import me.rgunny.kachi.ai.support.TestLlmServer
import me.rgunny.kachi.ai.support.TestStubResponse
import me.rgunny.kachi.ai.support.TestStubServer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.json.JsonMapper

/**
 * 요약 한 건이 LLM 호출 -> 저장 -> outbox -> relay -> broker까지 실제 인프라 위에서 완주하는지 본다.
 *
 * LLM·collector·user-service는 테스트 JVM 안의 stub이 받고, Mongo replica set과 Kafka는 컨테이너다.
 * broker에서 읽은 레코드는 소비자가 쓰는 계약 타입(`ai-contract`)으로 역직렬화한다. 그래야 계약 검증이다.
 * tick은 scheduler를 기다리지 않고 유스케이스를 직접 부른다. 실패 분류·watermark·격리 규칙 자체는 단위 테스트가 보고,
 * 여기서는 그 규칙이 실제 저장소·broker 경계를 지나서도 같은 결과를 내는지만 본다.
 *
 * stub의 포트는 컨텍스트보다 먼저 정해져야 하므로 `@DynamicPropertySource`로 base-url을 넣는다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "kachi.ai.events.enabled=true",
        "kachi.ai.outbox.relay.enabled=true",
        "kachi.ai.outbox.relay.initial-delay=1h",
        "kachi.ai.scheduler.news-summary.initial-delay=1h",
        "kachi.ai.quarantine.failure-threshold=2"
    ]
)
@Import(AiServiceTestContainersConfig::class)
class AiSummaryCycleIntegrationTest {

    @Autowired
    private lateinit var summarizeNewsUseCase: SummarizeNewsUseCase

    @Autowired
    private lateinit var relayUseCase: RelayAiOutboxUseCase

    @Autowired
    private lateinit var providerAdmin: LlmProviderAdminPort

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @Autowired
    private lateinit var kafkaContainer: KafkaContainer

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    private val outboxCollection by lazy { AiOutboxCollection(mongoTemplate) }

    @BeforeEach
    fun resetState() {
        llm.reset()
        upstream.reset()
        // 이전 테스트가 만든 cooldown이나 열린 회로가 다음 테스트의 호출을 막지 않게 한다.
        providerAdmin.reset(MODEL)
        listOf(
            AiOutboxMongoDocument::class.java,
            NewsSummaryMongoDocument::class.java,
            SummaryWatermarkMongoDocument::class.java,
            KeywordQuarantineMongoDocument::class.java,
            AiRunMongoDocument::class.java
        ).forEach { mongoTemplate.remove(it).all().block() }
    }

    @Test
    @DisplayName("요약 한 건이 outbox를 거쳐 broker에 계약 형태로 발행되고 watermark가 전진한다")
    fun publishSummaryCreatedEvent() = runBlocking {
        val keyword = uniqueKeyword("nvidia")
        val newsIds = stubNews(count = 3)
        llm.enqueueSummary(title = "NVIDIA 요약", content = "실적 호조", sentiment = "POSITIVE")

        val summarized = summarizeNewsUseCase.summarize(command(keyword))
        val relayed = relayUseCase.relay()

        assertEquals(1, summarized.succeededCount)
        assertTrue(summarized.watermarkAdvanced)
        assertEquals(1, relayed.published)
        assertEquals(1, llm.requests.size)
        assertTrue(llm.requests.single().contains(keyword))

        val summary = mongoTemplate.findAll(NewsSummaryMongoDocument::class.java).collectList().block()!!.single()
        assertEquals(newsIds.toSet(), summary.sourceNewsIds.toSet())
        // stub은 요청과 다른 이름으로 답한다. 응답이 보고한 모델과 요청한 모델이 각자 남아야 한다.
        assertEquals("test-model", summary.model)
        assertEquals(MODEL.code, summary.requestedModel)
        assertEquals(AiOutboxStatus.PUBLISHED.name, outboxCollection.findAll().single().status)
        assertNotNull(mongoTemplate.findAll(SummaryWatermarkMongoDocument::class.java).blockFirst())

        val record = recordsOf(AiTestFixture.EVENT_TOPIC_SUMMARY_CREATED, key = keyword, expected = 1).single()
        val event = jsonMapper.readValue(record.value(), AiSummaryCreatedEvent::class.java)
        assertEquals(AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion)
        assertEquals(summary.id.toString(), event.summaryId)
        assertEquals(keyword, event.keyword)
        assertEquals("NVIDIA 요약", event.title)
        assertEquals(3, event.sourceNewsCount)
        assertEquals(MODEL.provider.code, event.provider)
    }

    /**
     * 후보가 하나라 429 뒤의 cooldown이 곧 "호출할 모델 없음"이다. 둘째 키워드가 차단 실패로 끝나면서 나머지를 중단시킨다.
     */
    @Test
    @DisplayName("후보가 전부 쉬는 중이면 남은 키워드를 호출하지 않고 이벤트도 watermark도 남기지 않는다")
    fun stopEarlyWhenNoCandidateCanBeCalled() = runBlocking {
        val first = uniqueKeyword("rate-a")
        val second = uniqueKeyword("rate-b")
        val third = uniqueKeyword("rate-c")
        stubNews(count = 1)
        llm.enqueueRateLimited(retryAfterSeconds = 30)

        val summarized = summarizeNewsUseCase.summarize(command(first, second, third))
        val relayed = relayUseCase.relay()

        assertEquals(1, llm.requests.size)
        assertEquals(2, summarized.failureCount)
        assertEquals(1, summarized.skippedCount)
        assertFalse(summarized.watermarkAdvanced)
        assertEquals(0, relayed.published)
        assertTrue(outboxCollection.findAll().isEmpty())
        assertTrue(mongoTemplate.findAll(SummaryWatermarkMongoDocument::class.java).collectList().block()!!.isEmpty())
    }

    @Test
    @DisplayName("키워드 하나가 실패하면 성공한 키워드만 발행되고 watermark는 유지된다")
    fun keepWatermarkOnPartialFailure() = runBlocking {
        val succeeded = uniqueKeyword("partial-ok")
        val failed = uniqueKeyword("partial-bad")
        stubNews(count = 2)
        llm.enqueueSummary()
        llm.enqueueClientError()

        val summarized = summarizeNewsUseCase.summarize(command(succeeded, failed))
        val relayed = relayUseCase.relay()

        assertEquals(1, summarized.succeededCount)
        assertEquals(1, summarized.failureCount)
        assertFalse(summarized.watermarkAdvanced)
        assertEquals(1, relayed.published)
        assertEquals(1, recordsOf(AiTestFixture.EVENT_TOPIC_SUMMARY_CREATED, key = succeeded, expected = 1).size)
        assertEquals(1, quarantineOf(failed).consecutiveFailures)
    }

    @Test
    @DisplayName("키워드 실패가 임계치에 닿으면 격리 이벤트가 broker에 발행된다")
    fun publishKeywordQuarantinedEvent() = runBlocking {
        val keyword = uniqueKeyword("quarantine")
        stubNews(count = 1)
        llm.enqueueClientError()
        llm.enqueueClientError()

        summarizeNewsUseCase.summarize(command(keyword))
        summarizeNewsUseCase.summarize(command(keyword))
        val relayed = relayUseCase.relay()

        assertEquals(1, relayed.published)
        val record = recordsOf(AiTestFixture.EVENT_TOPIC_KEYWORD_QUARANTINED, key = keyword, expected = 1).single()
        val event = jsonMapper.readValue(record.value(), AiKeywordQuarantinedEvent::class.java)
        assertEquals(AiKeywordQuarantinedEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion)
        assertEquals(keyword, event.keyword)
        assertEquals(2, event.consecutiveFailures)
        assertEquals(AiTargetType.NEWS_SUMMARY, event.targetType)
    }

    @Test
    @DisplayName("발행 중에 멈춘 행은 회수되어 broker까지 간다")
    fun recoverStalePublishingRow() = runBlocking {
        val key = uniqueKeyword("stale")
        val staleClaimedAt = Instant.now().minus(Duration.ofMinutes(10))
        outboxCollection.insert(
            AiTestFixture.restoredOutbox(
                eventKey = key,
                partitionKey = key,
                status = AiOutboxStatus.PUBLISHING,
                claim = AiOutboxClaim(claimedBy = "dead-publisher", claimedAt = staleClaimedAt),
                nextRetryAt = staleClaimedAt,
                createdAt = staleClaimedAt
            )
        )

        val first = relayUseCase.relay()
        assertEquals(1, first.staleRecovered)
        // 회수는 실패 1회로 기록되어 재시도 backoff가 붙는다. 그 시각이 지나 발행될 때까지 tick을 반복한다.
        var published = first.published
        val deadline = Instant.now().plus(POLL_TIMEOUT)
        while (published == 0 && Instant.now().isBefore(deadline)) {
            delay(500)
            published += relayUseCase.relay().published
        }
        assertEquals(1, published)

        assertEquals(AiOutboxStatus.PUBLISHED.name, outboxCollection.findAll().single().status)
        val record = recordsOf(AiTestFixture.EVENT_TOPIC_SUMMARY_CREATED, key = key, expected = 1).single()
        assertEquals(AiTestFixture.OUTBOX_PAYLOAD, record.value())
    }

    @Test
    @DisplayName("같은 뉴스 묶음을 다시 요약하면 저장된 요약을 재사용하고 이벤트를 다시 만들지 않는다")
    fun reuseSummaryForSameNews() = runBlocking {
        val keyword = uniqueKeyword("reuse")
        stubNews(count = 2)
        llm.enqueueSummary()

        summarizeNewsUseCase.summarize(command(keyword))
        val second = summarizeNewsUseCase.summarize(command(keyword))
        val relayed = relayUseCase.relay()

        assertEquals(1, second.succeededCount)
        assertEquals(1, llm.requests.size)
        assertEquals(1, mongoTemplate.findAll(NewsSummaryMongoDocument::class.java).collectList().block()!!.size)
        assertEquals(1, outboxCollection.findAll().size)
        assertEquals(1, relayed.published)
    }

    private fun command(vararg keywords: String): SummarizeNewsCommand {
        return SummarizeNewsCommand(
            keywords = keywords.map { AiTestFixture.keyword(it) },
            window = WatermarkSummaryWindowRequest(overlap = Duration.ofMinutes(5), maxLookback = Duration.ofHours(6))
        )
    }

    /**
     * collector-service가 돌려줄 기사를 두고 그 id를 돌려준다.
     *
     * 키워드는 query라 path가 같으므로 한 테스트의 모든 키워드가 같은 기사 묶음을 받는다. 요약의 유일 키는 키워드를 포함하므로 검증에는 지장이 없다.
     */
    private fun stubNews(count: Int): List<UUID> {
        val ids = List(count) { UUID.randomUUID() }
        val articles = ids.mapIndexed { index, id ->
            mapOf(
                "id" to id.toString(),
                "source" to "GOOGLE",
                "title" to "news $index",
                "url" to "https://news.example.com/$id",
                "publishedAt" to Instant.now().minus(Duration.ofMinutes(index + 1L)).toString(),
                "collectedAt" to Instant.now().toString(),
                "matchedKeywords" to emptyList<String>()
            )
        }
        val body = jsonMapper.writeValueAsString(mapOf("success" to true, "data" to articles))
        upstream.respond(NEWS_PATH, TestStubResponse(statusCode = 200, body = body))
        return ids
    }

    private fun quarantineOf(keyword: String): KeywordQuarantineMongoDocument {
        return mongoTemplate.findAll(KeywordQuarantineMongoDocument::class.java).collectList().block()!!
            .single { it.keyword == keyword }
    }

    private fun recordsOf(topic: String, key: String, expected: Int): List<ConsumerRecord<String, String>> {
        val properties = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "ai-cycle-test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )
        KafkaConsumer<String, String>(properties).use { consumer ->
            consumer.subscribe(listOf(topic))
            val found = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = Instant.now().plus(POLL_TIMEOUT)
            while (found.size < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach { record ->
                    if (record.key() == key) found += record
                }
            }
            return found
        }
    }

    private fun uniqueKeyword(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private val MODEL = LlmModel.OLLAMA_QWEN3_27B
        private const val NEWS_PATH = "/api/v1/internal/news"
        private val POLL_TIMEOUT: Duration = Duration.ofSeconds(20)

        private val llm = TestLlmServer()
        private val upstream = TestStubServer()

        @JvmStatic
        @DynamicPropertySource
        fun stubUrls(registry: DynamicPropertyRegistry) {
            registry.add("kachi.ai.llm.providers.${MODEL.provider.name}.base-url") { llm.baseUrl }
            registry.add("kachi.ai.clients.collector-service.base-url") { upstream.baseUrl }
            registry.add("kachi.ai.clients.user-service.base-url") { upstream.baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun closeStubs() {
            llm.close()
            upstream.close()
        }
    }
}
