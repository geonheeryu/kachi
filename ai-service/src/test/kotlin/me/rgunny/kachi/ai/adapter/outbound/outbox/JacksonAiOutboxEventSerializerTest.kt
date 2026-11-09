package me.rgunny.kachi.ai.adapter.outbound.outbox

import me.rgunny.kachi.ai.application.port.outbound.outbox.model.AiOutboxEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StoryQuarantinedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySplitRequestedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.StorySummaryCreatedEvent
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/**
 * payload 형식을 발행 계약으로 고정하는 테스트.
 *
 * outbox 행의 키(type, eventKey, partitionKey)는 payload에 들어가지 않는다.
 */
@DisplayName("JacksonAiOutboxEventSerializer")
class JacksonAiOutboxEventSerializerTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val serializer = JacksonAiOutboxEventSerializer(jsonMapper)

    @Test
    @DisplayName("story 요약 생성 이벤트를 schemaVersion 2 계약대로 직렬화한다")
    fun serializeStorySummaryCreated() {
        val summary = AiTestFixture.storySummary(version = 2, keywords = listOf("NVIDIA", "GPU"), sourceNewsCount = 4)
        val event = StorySummaryCreatedEvent.from(summary)

        val payload = parse(serializer.serialize(event))

        assertEquals(setOf("schemaVersion", "summaryId", "storyId", "version", "keywords", "developmentKind",
            "title", "content", "sentiment", "sourceNewsCount", "provider", "model", "promptVersion", "createdAt"), payload.keys)
        assertEquals(StorySummaryCreatedEvent.SCHEMA_VERSION, payload["schemaVersion"])
        assertEquals(summary.id.value.toString(), payload["summaryId"])
        assertEquals(AiTestFixture.STORY_ID.value.toString(), payload["storyId"])
        assertEquals(2, payload["version"])
        assertEquals(listOf("NVIDIA", "GPU"), payload["keywords"])
        assertEquals("DEVELOPMENT", payload["developmentKind"])
        assertEquals(summary.title, payload["title"])
        assertEquals(summary.content, payload["content"])
        assertEquals("NEUTRAL", payload["sentiment"])
        assertEquals(4, payload["sourceNewsCount"])
        assertEquals(AiTestFixture.PROVIDER.code, payload["provider"])
        assertEquals(AiTestFixture.MODEL, payload["model"])
        assertEquals(AiTestFixture.STORY_SUMMARY_PROMPT_VERSION.value, payload["promptVersion"])
        assertEquals("2026-06-03T00:00:00Z", payload["createdAt"])
    }

    @Test
    @DisplayName("분리 요청 이벤트를 계약대로 직렬화한다")
    fun serializeStorySplitRequested() {
        val summary = AiTestFixture.storySummary(version = 2, developmentKind = StoryDevelopmentKind.NEW_STORY)
        val event = StorySplitRequestedEvent.from(summary)

        val payload = parse(serializer.serialize(event))

        assertEquals(setOf("schemaVersion", "storyId", "summaryId", "newsIds", "requestedAt"), payload.keys)
        assertEquals(AiOutboxEvent.CURRENT_SCHEMA_VERSION, payload["schemaVersion"])
        assertEquals(AiTestFixture.STORY_ID.value.toString(), payload["storyId"])
        assertEquals(listOf(AiTestFixture.NEWS_ID.toString()), payload["newsIds"])
        assertEquals("2026-06-03T00:00:00Z", payload["requestedAt"])
    }

    @Test
    @DisplayName("story 격리 이벤트를 계약대로 직렬화한다")
    fun serializeStoryQuarantined() {
        val quarantine = AiTestFixture.storyQuarantine(consecutiveFailures = 3)
        val event = StoryQuarantinedEvent.from(quarantine)

        val payload = parse(serializer.serialize(event))

        assertEquals(setOf("schemaVersion", "quarantineId", "storyId", "consecutiveFailures",
            "lastFailureReason", "quarantinedAt"), payload.keys)
        assertEquals(AiOutboxEvent.CURRENT_SCHEMA_VERSION, payload["schemaVersion"])
        assertEquals(quarantine.id.value.toString(), payload["quarantineId"])
        assertEquals(AiTestFixture.STORY_ID.value.toString(), payload["storyId"])
        assertEquals(3, payload["consecutiveFailures"])
        assertEquals("UNKNOWN", payload["lastFailureReason"])
        assertEquals("2026-06-03T00:00:00Z", payload["quarantinedAt"])
    }

    private fun parse(json: String): Map<*, *> {
        return jsonMapper.readValue(json, Map::class.java)
    }
}
