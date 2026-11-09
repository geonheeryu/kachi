package me.rgunny.kachi.notification.routing

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.contract.NotificationRequestedEvent
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job.RoutingJobDocument
import me.rgunny.kachi.notification.routing.application.service.RoutingEventKey
import me.rgunny.kachi.notification.routing.application.service.RoutingRequestId
import me.rgunny.kachi.notification.routing.config.NotificationRoutingProperties
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture
import me.rgunny.kachi.notification.routing.support.TestStubResponse
import me.rgunny.kachi.notification.routing.support.TestStubServer
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
 * routing이 `ai.summary.created`(schemaVersion 2)와 `ai.story.quarantined` 레코드를 실제 broker에서 소비해 `notification.requested`로 발행하는 계약을 본다.
 *
 * 입력은 producer 계약 타입(`ai-contract`)으로 만들어 발행하고, 출력은 소비자 계약 타입(`notification-contract`)으로 읽는다.
 * user-service는 테스트 JVM 안의 stub이 받는다. broker 경계에서 key·payload가 계약과 맞는지만 본다.
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(RoutingTestContainersConfig::class)
class RoutingKafkaContractIntegrationTest {

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var kafkaContainer: KafkaContainer

    @Autowired
    private lateinit var properties: NotificationRoutingProperties

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @BeforeEach
    fun resetState() {
        userService.reset()
        mongoTemplate.remove(RoutingJobDocument::class.java).all().block()
    }

    @Test
    @DisplayName("v2 요약을 소비하면 두 페이지의 구독 수신자 x 채널 전부를 sum:{storyId}:{version} requestId key로 발행한다")
    fun publishRequestedEventsForSubscribers() {
        val storyId = uniqueId("story")
        val summaryId = UUID.randomUUID().toString()
        val version = 3L
        userService.enqueue(
            RoutingTestFixture.RECIPIENTS_SEARCH_PATH,
            recipientPage(
                recipients = """[{"userId":"user-1","channel":"SLACK","keywordIndexes":[0,1]},{"userId":"user-1","channel":"TELEGRAM","keywordIndexes":[1]}]""",
                nextCursor = "\"user-1\"",
            )
        )
        userService.enqueue(
            RoutingTestFixture.RECIPIENTS_SEARCH_PATH,
            recipientPage(
                recipients = """[{"userId":"user-2","channel":"DISCORD","keywordIndexes":[0]}]""",
                nextCursor = "null",
            )
        )
        val event = RoutingTestFixture.summaryCreatedEvent(summaryId = summaryId, storyId = storyId, version = version)

        kafkaTemplate.send(properties.topics.summaryCreated, storyId, jsonMapper.writeValueAsString(event)).get()

        val expectedKeywords = mapOf(
            RoutingRequestId.forSummary(storyId, version, "user-1", NotificationChannel.SLACK) to listOf("nvidia", "tsmc"),
            RoutingRequestId.forSummary(storyId, version, "user-1", NotificationChannel.TELEGRAM) to listOf("tsmc"),
            RoutingRequestId.forSummary(storyId, version, "user-2", NotificationChannel.DISCORD) to listOf("nvidia"),
        )
        val records = recordsOf(properties.requestTopic, expected = 3) { it.key() in expectedKeywords }
        assertEquals(expectedKeywords.keys, records.map { it.key() }.toSet())
        records.forEach { record ->
            val requested = jsonMapper.readValue(record.value(), NotificationRequestedEvent::class.java)
            assertEquals(NotificationRequestedEvent.CURRENT_SCHEMA_VERSION, requested.schemaVersion)
            assertEquals(record.key(), requested.requestId)
            assertEquals("notification-routing", requested.requester)
            val origin = requested.origin!!
            assertEquals(summaryId, origin.summaryId)
            assertEquals(storyId, origin.storyId)
            assertEquals(version, origin.version)
            assertEquals(expectedKeywords.getValue(record.key()), origin.keywords)
            assertEquals(requested.recipientId, origin.userId)
            assertTrue(requested.message.contains(event.title))
        }
        val requestBodies = userService.bodies.getValue(RoutingTestFixture.RECIPIENTS_SEARCH_PATH).map(jsonMapper::readTree)
        assertEquals(2, requestBodies.size)
        assertEquals(listOf("nvidia", "tsmc"), requestBodies[0].get("keywords").values().map { it.asString() })
        assertTrue(requestBodies[0].get("cursor").isNull)
        assertEquals("user-1", requestBodies[1].get("cursor").asString())
    }

    @Test
    @DisplayName("story 격리를 소비하면 관리자 x 채널만큼 qrt requestId key로 발행한다")
    fun publishRequestedEventsForAdmins() {
        val storyId = uniqueId("quarantined")
        userService.respond(
            RoutingTestFixture.USERS_PATH,
            TestStubResponse(
                statusCode = 200,
                body = """{"success":true,"data":[{"userId":"user-9","channels":["SLACK","TELEGRAM"]}]}"""
            )
        )
        val event = RoutingTestFixture.storyQuarantinedEvent(quarantineId = UUID.randomUUID().toString(), storyId = storyId)
        val eventKey = RoutingEventKey.forQuarantine(event.quarantineId, event.quarantinedAt)

        kafkaTemplate.send(properties.topics.storyQuarantined, storyId, jsonMapper.writeValueAsString(event)).get()

        val expectedKeys = setOf(
            RoutingRequestId.forQuarantine(eventKey, "user-9", NotificationChannel.SLACK),
            RoutingRequestId.forQuarantine(eventKey, "user-9", NotificationChannel.TELEGRAM)
        )
        val records = recordsOf(properties.requestTopic, expected = 2) { it.key() in expectedKeys }
        assertEquals(expectedKeys, records.map { it.key() }.toSet())
        records.forEach { record ->
            val requested = jsonMapper.readValue(record.value(), NotificationRequestedEvent::class.java)
            val origin = requested.origin!!
            assertEquals(storyId, origin.storyId)
            assertEquals("user-9", origin.userId)
            assertNull(origin.summaryId)
            assertNull(origin.version)
            assertTrue(origin.keywords.isEmpty())
            assertTrue(requested.message.contains(storyId))
        }
    }

    @Test
    @DisplayName("모르는 schemaVersion은 재시도 없이 DLT로 보내고 user-service를 부르지 않으며 접수 이벤트를 만들지 않는다")
    fun sendUnknownSchemaVersionToDlt() {
        val storyId = uniqueId("unknown-schema")
        val event = RoutingTestFixture.summaryCreatedEvent(storyId = storyId, summaryId = UUID.randomUUID().toString())
        val payload = jsonMapper.writeValueAsString(event).replace("\"schemaVersion\":2", "\"schemaVersion\":1")

        kafkaTemplate.send(properties.topics.summaryCreated, storyId, payload).get()

        val dlt = recordsOf(properties.dlt.topic, expected = 1) { it.key() == storyId }
        assertEquals(1, dlt.size)
        assertEquals(payload, dlt.single().value())
        // user-service를 부르기 전에 걸러진다.
        assertEquals(0, userService.requestCount(RoutingTestFixture.RECIPIENTS_SEARCH_PATH))
        assertTrue(recordsOf(properties.requestTopic, expected = 1, timeout = SHORT_TIMEOUT) { it.key().contains(storyId) }.isEmpty())
    }

    private fun recordsOf(
        topic: String,
        expected: Int,
        timeout: Duration = POLL_TIMEOUT,
        matches: (ConsumerRecord<String, String>) -> Boolean
    ): List<ConsumerRecord<String, String>> {
        val config = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaContainer.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "routing-contract-test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
        )
        KafkaConsumer<String, String>(config).use { consumer ->
            consumer.subscribe(listOf(topic))
            val found = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = Instant.now().plus(timeout)
            while (found.size < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach { record -> if (matches(record)) found += record }
            }
            return found
        }
    }

    private fun uniqueId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun recipientPage(recipients: String, nextCursor: String): TestStubResponse {
        return TestStubResponse(
            statusCode = 200,
            body = """{"success":true,"data":{"keywords":[{"canonicalKey":"nvidia","displayName":"NVIDIA"},{"canonicalKey":"tsmc","displayName":"TSMC"}],"recipients":$recipients,"nextCursor":$nextCursor},"error":null}"""
        )
    }

    companion object {
        private val POLL_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val SHORT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val userService = TestStubServer()

        @JvmStatic
        @DynamicPropertySource
        fun stubUrls(registry: DynamicPropertyRegistry) {
            registry.add("kachi.notification.routing.user-service.base-url") { userService.baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun closeStub() {
            userService.close()
        }
    }
}
