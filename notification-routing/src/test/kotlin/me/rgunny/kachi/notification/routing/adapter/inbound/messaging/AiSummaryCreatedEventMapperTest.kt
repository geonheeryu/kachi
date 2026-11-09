package me.rgunny.kachi.notification.routing.adapter.inbound.messaging

import me.rgunny.kachi.ai.contract.AiSummaryCreatedEvent
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.SummarySentiment
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture.NOW
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("AiSummaryCreatedEventMapper")
class AiSummaryCreatedEventMapperTest {

    @Test
    @DisplayName("v2 이벤트를 routing command로 옮기고 감성을 routing 값으로 바꾼다")
    fun toCommand() {
        val command = AiSummaryCreatedEventMapper.toCommand(RoutingTestFixture.summaryCreatedEvent())

        assertEquals(
            RouteSummaryCommand(
                summaryId = "summary-1",
                storyId = "story-1",
                version = 1,
                keywords = listOf("nvidia", "tsmc"),
                title = "NVIDIA and TSMC expand partnership",
                content = "NVIDIA and TSMC announced a new plant.",
                sentiment = SummarySentiment.POSITIVE,
                sourceNewsCount = 3,
                createdAt = NOW,
            ),
            command,
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    @DisplayName("schemaVersion이 2가 아니면 거부한다")
    fun rejectSchemaVersion(schemaVersion: Int) {
        assertFailsWith<IllegalArgumentException> {
            AiSummaryCreatedEventMapper.toCommand(RoutingTestFixture.summaryCreatedEvent(schemaVersion = schemaVersion))
        }
    }

    @Test
    @DisplayName("keywords가 비었거나 100개를 넘으면 거부한다")
    fun rejectKeywordCount() {
        assertFailsWith<IllegalArgumentException> {
            AiSummaryCreatedEventMapper.toCommand(RoutingTestFixture.summaryCreatedEvent(keywords = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            AiSummaryCreatedEventMapper.toCommand(RoutingTestFixture.summaryCreatedEvent(keywords = (1..101).map { "k$it" }))
        }
        assertEquals(
            100,
            AiSummaryCreatedEventMapper.toCommand(RoutingTestFixture.summaryCreatedEvent(keywords = (1..100).map { "k$it" }))
                .keywords.size,
        )
    }

    @Test
    @DisplayName("version이 1보다 작거나 storyId·summaryId·title이 비면 거부한다")
    fun rejectInvalidFields() {
        assertFailsWith<IllegalArgumentException> { toCommand(RoutingTestFixture.summaryCreatedEvent(version = 0)) }
        assertFailsWith<IllegalArgumentException> { toCommand(RoutingTestFixture.summaryCreatedEvent(storyId = " ")) }
        assertFailsWith<IllegalArgumentException> { toCommand(RoutingTestFixture.summaryCreatedEvent(summaryId = "")) }
        assertFailsWith<IllegalArgumentException> { toCommand(RoutingTestFixture.summaryCreatedEvent(title = " ")) }
    }

    private fun toCommand(event: AiSummaryCreatedEvent): RouteSummaryCommand {
        return AiSummaryCreatedEventMapper.toCommand(event)
    }
}
