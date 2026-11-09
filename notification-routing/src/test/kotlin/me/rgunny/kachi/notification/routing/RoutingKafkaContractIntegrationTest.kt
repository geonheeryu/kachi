package me.rgunny.kachi.notification.routing

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.rgunny.kachi.ai.contract.AiKeywordQuarantinedEvent
import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.contract.NotificationRequestedEvent
import me.rgunny.kachi.notification.routing.adapter.inbound.messaging.AiKeywordQuarantinedEventMapper
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job.RoutingJobDocument
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
 * routing이 `ai.*` 레코드를 실제 broker에서 소비해 `notification.requested`로 발행하는 계약을 본다.
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
    @DisplayName("요약 이벤트를 소비하면 구독자 x 채널만큼 requestId를 key로 접수 이벤트를 발행한다")
    fun publishRequestedEventsForSubscribers() {
        val keyword = uniqueKeyword("tesla")
        val summaryId = UUID.randomUUID().toString()
        userService.respond(
            RoutingTestFixture.RECIPIENTS_SEARCH_PATH,
            TestStubResponse(
                statusCode = 200,
                body = """{"success":true,"data":{"keywords":[{"canonicalKey":"$keyword","displayName":"$keyword"}],"recipients":[{"userId":"user-1","channel":"SLACK","keywordIndexes":[0]},{"userId":"user-1","channel":"TELEGRAM","keywordIndexes":[0]},{"userId":"user-2","channel":"DISCORD","keywordIndexes":[0]}],"nextCursor":null},"error":null}"""
            )
        )
        val event = RoutingTestFixture.summaryCreatedEvent(summaryId = summaryId, keyword = keyword)

        kafkaTemplate.send(properties.topics.summaryCreated, keyword, jsonMapper.writeValueAsString(event)).get()

        val expectedKeys = setOf(
            RoutingRequestId.forSummary(summaryId, "user-1", NotificationChannel.SLACK),
            RoutingRequestId.forSummary(summaryId, "user-1", NotificationChannel.TELEGRAM),
            RoutingRequestId.forSummary(summaryId, "user-2", NotificationChannel.DISCORD)
        )
        val records = recordsOf(properties.requestTopic, expected = 3) { it.key() in expectedKeys }
        assertEquals(expectedKeys, records.map { it.key() }.toSet())
        records.forEach { record ->
            val requested = jsonMapper.readValue(record.value(), NotificationRequestedEvent::class.java)
            assertEquals(NotificationRequestedEvent.CURRENT_SCHEMA_VERSION, requested.schemaVersion)
            assertEquals(record.key(), requested.requestId)
            assertEquals("notification-routing", requested.requester)
            assertEquals(summaryId, requested.origin?.summaryId)
            assertEquals(keyword, requested.origin?.keyword)
            assertTrue(requested.message.contains(event.title))
        }
        assertEquals(1, userService.requestCount(RoutingTestFixture.RECIPIENTS_SEARCH_PATH))
    }

    @Test
    @DisplayName("격리 이벤트를 소비하면 관리자 x 채널만큼 qrt requestId로 발행한다")
    fun publishRequestedEventsForAdmins() {
        val keyword = uniqueKeyword("quarantined")
        userService.respond(
            RoutingTestFixture.USERS_PATH,
            TestStubResponse(
                statusCode = 200,
                body = """{"success":true,"data":[{"userId":"admin-1","channels":["SLACK","TELEGRAM"]}]}"""
            )
        )
        val event = RoutingTestFixture.keywordQuarantinedEvent(quarantineId = UUID.randomUUID().toString(), keyword = keyword)
        val eventKey = AiKeywordQuarantinedEventMapper.eventKey(event)

        kafkaTemplate.send(properties.topics.keywordQuarantined, keyword, jsonMapper.writeValueAsString(event)).get()

        val expectedKeys = setOf(
            RoutingRequestId.forQuarantine(eventKey, "admin-1", NotificationChannel.SLACK),
            RoutingRequestId.forQuarantine(eventKey, "admin-1", NotificationChannel.TELEGRAM)
        )
        val records = recordsOf(properties.requestTopic, expected = 2) { it.key() in expectedKeys }
        assertEquals(expectedKeys, records.map { it.key() }.toSet())
        records.forEach { record ->
            val requested = jsonMapper.readValue(record.value(), NotificationRequestedEvent::class.java)
            assertEquals(keyword, requested.origin?.keyword)
            assertTrue(requested.message.contains(keyword))
        }
    }

    @Test
    @DisplayName("모르는 schemaVersion은 재시도 없이 DLT로 보내고 접수 이벤트를 만들지 않는다")
    fun sendUnknownSchemaVersionToDlt() {
        val keyword = uniqueKeyword("unknown-schema")
        val event = RoutingTestFixture.summaryCreatedEvent(
            schemaVersion = AiSummaryCreatedEvent.CURRENT_SCHEMA_VERSION + 1,
            summaryId = UUID.randomUUID().toString(),
            keyword = keyword
        )

        kafkaTemplate.send(properties.topics.summaryCreated, keyword, jsonMapper.writeValueAsString(event)).get()

        val dlt = recordsOf(properties.dlt.topic, expected = 1) { it.key() == keyword }
        assertEquals(1, dlt.size)
        assertEquals(jsonMapper.writeValueAsString(event), dlt.single().value())
        // user-service를 부르기 전에 걸러진다.
        assertEquals(0, userService.requestCount(RoutingTestFixture.RECIPIENTS_SEARCH_PATH))
        assertTrue(recordsOf(properties.requestTopic, expected = 1, timeout = SHORT_TIMEOUT) { it.key().contains(event.summaryId) }.isEmpty())
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

    private fun uniqueKeyword(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"

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
