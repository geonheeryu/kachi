package me.rgunny.kachi.ai

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.adapter.outbound.persistence.outbox.AiOutboxMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.quarantine.StoryQuarantineMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.story.AiStoryArticleMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.story.AiStoryMongoDocument
import me.rgunny.kachi.ai.adapter.outbound.persistence.summary.StorySummaryMongoDocument
import me.rgunny.kachi.ai.application.port.inbound.outbox.RelayAiOutboxUseCase
import me.rgunny.kachi.ai.application.port.outbound.llm.LlmProviderAdminPort
import me.rgunny.kachi.ai.contract.AiDevelopmentKind
import me.rgunny.kachi.ai.contract.AiStorySplitRequestedEvent
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEventV2
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.AiOutboxCollection
import me.rgunny.kachi.ai.support.TestLlmServer
import me.rgunny.kachi.story.contract.StoryArticleAttachedEvent
import me.rgunny.kachi.story.contract.StoryArticleSource
import me.rgunny.kachi.story.contract.StoryMergedEvent
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
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.json.JsonMapper

/**
 * story 이벤트 한 건이 소비 -> 사본 기록 -> 즉시 트리거 -> LLM 호출 -> 버전 저장 -> outbox -> relay -> broker까지 완주하는지 보는 통합 테스트.
 *
 * LLM은 테스트 JVM 안의 stub이 받고, Mongo replica set과 Kafka는 컨테이너다.
 * broker에서 읽은 레코드는 소비자가 쓰는 계약 타입(`ai-contract`)으로 역직렬화한다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "kachi.ai.events.enabled=true",
        "kachi.ai.story-summary.min-new-articles=3",
        "kachi.ai.outbox.relay.enabled=true",
        "kachi.ai.outbox.relay.initial-delay=1h",
        "kachi.ai.scheduler.news-summary.initial-delay=1h"
    ]
)
@Import(AiServiceTestContainersConfig::class)
class AiStorySummaryCycleIntegrationTest {

    @Autowired
    private lateinit var relayUseCase: RelayAiOutboxUseCase

    @Autowired
    private lateinit var providerAdmin: LlmProviderAdminPort

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @Autowired
    private lateinit var kafkaContainer: KafkaContainer

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    private val outboxCollection by lazy { AiOutboxCollection(mongoTemplate) }

    @BeforeEach
    fun resetState() {
        llm.reset()
        providerAdmin.reset(MODEL)
        listOf(
            AiOutboxMongoDocument::class.java,
            AiStoryMongoDocument::class.java,
            AiStoryArticleMongoDocument::class.java,
            StorySummaryMongoDocument::class.java,
            StoryQuarantineMongoDocument::class.java
        ).forEach { mongoTemplate.remove(it).all().block() }
    }

    @Test
    @DisplayName("기사 3건이 붙으면 즉시 요약되어 schemaVersion 2 계약으로 발행된다")
    fun publishStorySummaryCreatedEvent() = runBlocking {
        val storyId = UUID.randomUUID()
        llm.enqueueStorySummary(title = "NVIDIA 사건 요약", content = "전개 요약")

        publishAttached(storyId, count = 3, keywords = listOf("nvidia", "gpu"))
        val summary = awaitSummary(storyId)

        assertEquals(1, summary.version)
        assertEquals(3, summary.sourceNewsCount)
        assertEquals(1, llm.requests.size)
        assertEquals(
            0,
            mongoTemplate.findAll(AiStoryArticleMongoDocument::class.java).collectList().block()!!
                .count { it.summarizedInVersion == null }
        )

        val relayed = relayUseCase.relay()
        assertEquals(1, relayed.published)
        val record = recordsOf(AiTestFixture.EVENT_TOPIC_SUMMARY_CREATED, key = storyId.toString(), expected = 1).single()
        val event = jsonMapper.readValue(record.value(), AiSummaryCreatedEventV2::class.java)
        assertEquals(AiSummaryCreatedEventV2.CURRENT_SCHEMA_VERSION, event.schemaVersion)
        assertEquals(storyId.toString(), event.storyId)
        assertEquals(1, event.version)
        assertEquals(listOf("nvidia", "gpu"), event.keywords)
        assertEquals(AiDevelopmentKind.DEVELOPMENT, event.developmentKind)
        assertEquals("NVIDIA 사건 요약", event.title)
        assertEquals(3, event.sourceNewsCount)
    }

    @Test
    @DisplayName("병합 이벤트가 미요약을 흡수한 story로 옮기고 임계에 닿으면 요약한다")
    fun mergeTriggersSummary() = runBlocking {
        val absorbing = UUID.randomUUID()
        val absorbed = UUID.randomUUID()
        llm.enqueueStorySummary()

        publishAttached(absorbing, count = 2, keywords = listOf("nvidia"))
        publishAttached(absorbed, count = 2, keywords = listOf("gpu"))
        awaitCondition("두 story의 기사 4건 소비") {
            mongoTemplate.findAll(AiStoryArticleMongoDocument::class.java).collectList().block()!!.size == 4
        }

        kafkaTemplate.send(
            AiTestFixture.EVENT_TOPIC_STORY_MERGED,
            absorbing.toString(),
            jsonMapper.writeValueAsString(
                StoryMergedEvent(
                    storyId = absorbing.toString(),
                    mergedStoryId = absorbed.toString(),
                    mergedAt = Instant.now()
                )
            )
        ).get()

        val summary = awaitSummary(absorbing)
        assertEquals(4, summary.sourceNewsCount)
        assertEquals(setOf("nvidia", "gpu"), summary.keywords.toSet())
        val absorbedState = mongoTemplate.findById(absorbed, AiStoryMongoDocument::class.java).block()!!
        assertEquals(absorbing, absorbedState.mergedInto)
    }

    @Test
    @DisplayName("NEW_STORY 판정이면 요약 알림 대신 분리 요청이 발행된다")
    fun publishSplitRequestOnNewStory() = runBlocking {
        val storyId = UUID.randomUUID()
        llm.enqueueStorySummary()
        llm.enqueueStorySummary(developmentKind = "NEW_STORY")

        publishAttached(storyId, count = 3, keywords = listOf("nvidia"))
        awaitSummary(storyId)
        publishAttached(storyId, count = 3, keywords = listOf("nvidia"), startIndex = 3)
        awaitCondition("v2 저장") {
            mongoTemplate.findAll(StorySummaryMongoDocument::class.java).collectList().block()!!
                .any { it.storyId == storyId && it.version == 2L }
        }

        relayUseCase.relay()
        val record = recordsOf(AiTestFixture.EVENT_TOPIC_STORY_SPLIT_REQUESTED, key = storyId.toString(), expected = 1).single()
        val event = jsonMapper.readValue(record.value(), AiStorySplitRequestedEvent::class.java)
        assertEquals(storyId.toString(), event.storyId)
        assertEquals(3, event.newsIds.size)
    }

    @Test
    @DisplayName("계약에 맞지 않는 메시지는 재시도 없이 DLT로 간다")
    fun sendInvalidMessageToDlt() = runBlocking {
        val key = UUID.randomUUID().toString()

        kafkaTemplate.send(AiTestFixture.EVENT_TOPIC_STORY_ARTICLE_ATTACHED, key, """{"schemaVersion":9}""").get()

        val record = recordsOf(DLT_TOPIC, key = key, expected = 1).single()
        assertEquals("""{"schemaVersion":9}""", record.value())
        assertTrue(mongoTemplate.findAll(AiStoryMongoDocument::class.java).collectList().block()!!.isEmpty())
    }

    private fun publishAttached(
        storyId: UUID,
        count: Int,
        keywords: List<String>,
        startIndex: Int = 0
    ) {
        (startIndex until startIndex + count).forEach { index ->
            val event = StoryArticleAttachedEvent(
                storyId = storyId.toString(),
                newsId = UUID.randomUUID().toString(),
                title = "기사 $index",
                excerpt = "발췌문 $index",
                url = "https://news.example.com/$storyId/$index",
                source = StoryArticleSource.GOOGLE,
                publishedAt = Instant.now().minusSeconds(600),
                storyKeywords = keywords,
                storyArticleCount = index + 1,
                attachedAt = Instant.now().minusSeconds((count - index).toLong())
            )
            kafkaTemplate.send(
                AiTestFixture.EVENT_TOPIC_STORY_ARTICLE_ATTACHED,
                storyId.toString(),
                jsonMapper.writeValueAsString(event)
            ).get()
        }
    }

    private suspend fun awaitSummary(storyId: UUID): StorySummaryMongoDocument {
        awaitCondition("storyId=$storyId 요약 저장") {
            mongoTemplate.findAll(StorySummaryMongoDocument::class.java).collectList().block()!!
                .any { it.storyId == storyId }
        }

        return mongoTemplate.findAll(StorySummaryMongoDocument::class.java).collectList().block()!!
            .filter { it.storyId == storyId }
            .maxByOrNull { it.version }!!
    }

    private suspend fun awaitCondition(
        description: String,
        condition: () -> Boolean
    ) {
        val deadline = Instant.now().plus(POLL_TIMEOUT)
        while (!condition()) {
            check(Instant.now().isBefore(deadline)) { "제한 시간 안에 끝나지 않았습니다: $description" }
            delay(200)
        }
    }

    private fun recordsOf(topic: String, key: String, expected: Int): List<ConsumerRecord<String, String>> {
        val properties = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "ai-story-cycle-test-${UUID.randomUUID()}",
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

    companion object {
        private val MODEL = LlmModel.OLLAMA_QWEN3_27B
        private val POLL_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val DLT_TOPIC = "ai.story.dlt"

        private val llm = TestLlmServer()

        @JvmStatic
        @DynamicPropertySource
        fun stubUrls(registry: DynamicPropertyRegistry) {
            registry.add("kachi.ai.llm.providers.${MODEL.provider.name}.base-url") { llm.baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun closeStubs() {
            llm.close()
        }
    }
}
