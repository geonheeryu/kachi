package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("AiEventsProperties")
class AiEventsPropertiesTest {

    @Test
    @DisplayName("topic은 비어 있을 수 없다")
    fun rejectBlankTopics() {
        assertFailsWith<IllegalArgumentException> { AiTestFixture.eventsProperties(summaryCreatedTopic = " ") }
        assertFailsWith<IllegalArgumentException> { AiTestFixture.eventsProperties(storySplitRequestedTopic = " ") }
        assertFailsWith<IllegalArgumentException> { AiTestFixture.eventsProperties(storyQuarantinedTopic = "") }
    }

    @ParameterizedTest
    @EnumSource(AiOutboxEventType::class)
    @DisplayName("이벤트 종류마다 설정된 topic을 고른다")
    fun pickTopicByEventType(eventType: AiOutboxEventType) {
        val properties = AiTestFixture.eventsProperties()

        val expected = when (eventType) {
            AiOutboxEventType.SUMMARY_CREATED -> AiTestFixture.EVENT_TOPIC_SUMMARY_CREATED
            AiOutboxEventType.STORY_SPLIT_REQUESTED -> AiTestFixture.EVENT_TOPIC_STORY_SPLIT_REQUESTED
            AiOutboxEventType.STORY_QUARANTINED -> AiTestFixture.EVENT_TOPIC_STORY_QUARANTINED
        }
        assertEquals(expected, properties.topicOf(eventType))
    }
}
