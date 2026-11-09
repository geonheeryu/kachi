package me.rgunny.kachi.story

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.collector.contract.CollectorNewsCollectedEvent
import me.rgunny.kachi.collector.contract.CollectorNewsSource
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryArticleMongoDocument
import me.rgunny.kachi.story.adapter.outbound.persistence.story.StoryMongoDocument
import me.rgunny.kachi.story.adapter.outbound.candidateindex.QdrantCollection
import me.rgunny.kachi.story.adapter.outbound.tei.TeiHttpClient
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateQuery
import me.rgunny.kachi.story.config.StoryConsumerProperties
import me.rgunny.kachi.story.contract.StoryArticleAttachedEvent
import me.rgunny.kachi.story.domain.EmbeddingModel
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.support.StoryOutboxCollection
import me.rgunny.kachi.story.support.TestStubResponse
import me.rgunny.kachi.story.support.TestStubServer
import io.qdrant.client.QdrantClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.json.JsonMapper

/**
 * story-service가 `collector.news.collected` 레코드를 실제 broker에서 소비해 story·기사·outbox·색인을 남기는 계약을 본다.
 *
 * 입력은 producer 계약 타입(`collector-contract`)으로 만들어 발행하고, outbox payload는 소비자 계약 타입(`story-contract`)으로 읽는다.
 * 임베딩 서버는 테스트 JVM 안의 stub이 받아 고정 벡터를 돌려준다.
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(StoryServiceTestContainersConfig::class)
class StoryAssemblyKafkaIntegrationTest {

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var kafkaContainer: KafkaContainer

    @Autowired
    private lateinit var properties: StoryConsumerProperties

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var candidateIndexPort: CandidateIndexPort

    @Autowired
    private lateinit var qdrantClient: QdrantClient

    @Autowired
    private lateinit var collection: QdrantCollection

    @Autowired
    @Qualifier("teiEmbeddingStub")
    private lateinit var embeddingStub: TestStubServer

    private val outboxCollection by lazy { StoryOutboxCollection(mongoTemplate) }

    @BeforeEach
    fun resetState() {
        mongoTemplate.remove(StoryMongoDocument::class.java).all().block()
        mongoTemplate.remove(StoryArticleMongoDocument::class.java).all().block()
        outboxCollection.clear()
        runBlocking { candidateIndexPort.deleteCollectedBefore(FAR_FUTURE) }
        embeddingStub.respond(TeiHttpClient.EMBED_PATH, embedResponse(1f, 0f))
    }

    @Test
    @DisplayName("기사 이벤트를 소비하면 story·기사·outbox·색인 점이 하나씩 남고 payload는 story 계약으로 읽힌다")
    fun attachFirstArticle() {
        val event = event()

        publish(event)
        awaitArticles(1)

        val story = mongoTemplate.findAll(StoryMongoDocument::class.java).collectList().block()!!.single()
        assertEquals(1, story.articleCount)
        assertEquals(0, story.version)
        assertEquals(listOf("nvidia"), story.keywords)
        assertEquals(story.id, mongoTemplate.findAll(StoryArticleMongoDocument::class.java).collectList().block()!!.single().storyId)

        val outbox = outboxCollection.findAll().single()
        val payload = jsonMapper.readValue(outbox.payload, StoryArticleAttachedEvent::class.java)
        assertEquals(StoryArticleAttachedEvent.CURRENT_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(story.id.toString(), payload.storyId)
        assertEquals(event.newsId, payload.newsId)
        assertEquals(event.title, payload.title)
        assertEquals(1, payload.storyArticleCount)
        assertEquals("${story.id}:${event.newsId}", outbox.eventKey)

        assertEquals(1, pointCount())
    }

    @Test
    @DisplayName("같은 벡터의 두 번째 기사는 judge 없이 같은 story에 붙는다")
    fun autoMergeSecondArticle() {
        val first = event()
        val second = event(title = "엔비디아 실적 서프라이즈")

        publish(first)
        awaitArticles(1)
        publish(second)
        awaitArticles(2)

        val story = mongoTemplate.findAll(StoryMongoDocument::class.java).collectList().block()!!.single()
        assertEquals(2, story.articleCount)
        assertEquals(1, story.version)
        assertEquals(2, outboxCollection.findAll().size)
        assertEquals(2, pointCount())
        val hits = runBlocking {
            candidateIndexPort.search(
                CandidateQuery(
                    embedding = StoryTestFixture.embedding(1f, 0f),
                    collectedAfter = Instant.now().minus(Duration.ofDays(1)),
                    limit = 10
                )
            )
        }
        assertEquals(setOf(story.id), hits.map { it.storyId.value }.toSet())
    }

    @Test
    @DisplayName("직교하는 벡터의 기사는 새 story를 연다")
    fun openNewStoryForUnrelatedArticle() {
        publish(event())
        awaitArticles(1)
        embeddingStub.enqueue(TeiHttpClient.EMBED_PATH, embedResponse(0f, 1f))

        publish(event(title = "테슬라 로보택시 출시"))
        awaitArticles(2)

        assertEquals(2, mongoTemplate.count(Query(), StoryMongoDocument::class.java).block())
        assertEquals(2, outboxCollection.findAll().size)
    }

    @Test
    @DisplayName("같은 newsId가 다시 오면 기사·outbox 수가 늘지 않는다")
    fun replayDuplicateNewsId() {
        val first = event()

        publish(first)
        awaitArticles(1)
        publish(first)
        publish(event(title = "세 번째 기사"))
        awaitArticles(2)

        assertEquals(2, outboxCollection.findAll().size)
        assertEquals(1, mongoTemplate.count(Query(), StoryMongoDocument::class.java).block())
        assertEquals(2, pointCount())
    }

    @Test
    @DisplayName("모르는 schemaVersion은 재시도 없이 DLT로 보내고 아무것도 저장하지 않는다")
    fun sendUnknownSchemaVersionToDlt() {
        val event = event(schemaVersion = CollectorNewsCollectedEvent.CURRENT_SCHEMA_VERSION + 1)
        val payload = jsonMapper.writeValueAsString(event)
        // 컨텍스트를 공유하는 테스트 사이에 누적되는 stub 요청 수의 기준점
        val embedCallsBefore = embeddingStub.requestCount(TeiHttpClient.EMBED_PATH)

        kafkaTemplate.send(properties.topics.newsCollected, event.newsId, payload).get()

        val dlt = recordsOf(properties.dlt.topic, expected = 1) { it.key() == event.newsId }
        assertEquals(payload, dlt.single().value())
        assertEquals(0, mongoTemplate.count(Query(), StoryArticleMongoDocument::class.java).block())
        assertEquals(embedCallsBefore, embeddingStub.requestCount(TeiHttpClient.EMBED_PATH))
    }

    private fun publish(event: CollectorNewsCollectedEvent) {
        kafkaTemplate.send(properties.topics.newsCollected, event.newsId, jsonMapper.writeValueAsString(event)).get()
    }

    private fun awaitArticles(expected: Long) {
        val deadline = Instant.now().plus(POLL_TIMEOUT)
        while (Instant.now().isBefore(deadline)) {
            val count = mongoTemplate.count(Query(), StoryArticleMongoDocument::class.java).block()!!
            if (count >= expected) {
                assertEquals(expected, count)
                return
            }
            Thread.sleep(200)
        }
        assertEquals(expected, mongoTemplate.count(Query(), StoryArticleMongoDocument::class.java).block())
    }

    private fun pointCount(): Long {
        return runBlocking { qdrantClient.countAsync(collection.name).await() }
    }

    private fun recordsOf(
        topic: String,
        expected: Int,
        matches: (ConsumerRecord<String, String>) -> Boolean
    ): List<ConsumerRecord<String, String>> {
        val config = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "story-assembly-test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )
        KafkaConsumer<String, String>(config).use { consumer ->
            consumer.subscribe(listOf(topic))
            val found = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = Instant.now().plus(POLL_TIMEOUT)
            while (found.size < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach { record -> if (matches(record)) found += record }
            }
            assertTrue(found.size >= expected, "topic $topic 에서 레코드 $expected 건을 기다렸으나 ${found.size} 건이다")

            return found
        }
    }

    private fun event(
        schemaVersion: Int = CollectorNewsCollectedEvent.CURRENT_SCHEMA_VERSION,
        title: String = "NVIDIA 실적 발표"
    ): CollectorNewsCollectedEvent {
        val now = Instant.now()

        return CollectorNewsCollectedEvent(
            schemaVersion = schemaVersion,
            newsId = UUID.randomUUID().toString(),
            source = CollectorNewsSource.NAVER,
            title = title,
            excerpt = "$title 관련 발췌문",
            url = "https://kachi.com/news/${UUID.randomUUID()}",
            language = "ko",
            publishedAt = now.minus(Duration.ofHours(1)),
            collectedAt = now,
            matchedKeywords = listOf("nvidia")
        )
    }

    /**
     * 앞자리 몇 개만 준 1024차원 벡터 하나를 `/embed` 응답 형식으로 만든다.
     */
    private fun embedResponse(vararg leading: Float): TestStubResponse {
        val values = FloatArray(EmbeddingModel.BGE_M3.dimension)
        leading.forEachIndexed { i, v -> values[i] = v }

        return TestStubResponse(statusCode = 200, body = values.joinToString(",", "[[", "]]"))
    }

    private companion object {
        val POLL_TIMEOUT: Duration = Duration.ofSeconds(30)
        val FAR_FUTURE: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
