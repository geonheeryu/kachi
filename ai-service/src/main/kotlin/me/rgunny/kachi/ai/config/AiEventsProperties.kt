package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * outbox 이벤트를 Kafka topic으로 발행하는 설정.
 *
 * [enabled]는 발행 어댑터의 스위치다(relay의 스위치와 별개).
 * topic은 이벤트 종류마다 하나이며 [topicOf]가 고른다.
 */
@ConfigurationProperties(prefix = AiEventsProperties.PREFIX)
data class AiEventsProperties(
    val enabled: Boolean,
    val topics: Topics
) {
    companion object {
        const val PREFIX = "kachi.ai.events"
    }

    data class Topics(
        val summaryCreated: String,
        val storySplitRequested: String,
        val storyQuarantined: String
    ) {
        init {
            require(summaryCreated.isNotBlank()) { "summary-created topic은 비어 있을 수 없습니다" }
            require(storySplitRequested.isNotBlank()) { "story-split-requested topic은 비어 있을 수 없습니다" }
            require(storyQuarantined.isNotBlank()) { "story-quarantined topic은 비어 있을 수 없습니다" }
        }
    }

    fun topicOf(eventType: AiOutboxEventType): String {
        return when (eventType) {
            AiOutboxEventType.SUMMARY_CREATED -> topics.summaryCreated
            AiOutboxEventType.STORY_SPLIT_REQUESTED -> topics.storySplitRequested
            AiOutboxEventType.STORY_QUARANTINED -> topics.storyQuarantined
        }
    }
}
