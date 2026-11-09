package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.qdrant.client.ValueFactory.value
import io.qdrant.client.grpc.JsonWithInt.Value
import java.util.UUID
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.domain.StoryId

/**
 * 점 하나에 붙는 payload의 필드 이름과 변환.
 *
 * `collectedAt`은 epoch millis 정수다.
 */
object QdrantPayload {
    const val STORY_ID = "storyId"
    const val COLLECTED_AT = "collectedAt"
    const val LANGUAGE = "language"

    fun of(article: IndexedArticle): Map<String, Value> {
        return mapOf(
            STORY_ID to value(article.storyId.value.toString()),
            COLLECTED_AT to value(article.collectedAt.toEpochMilli()),
            LANGUAGE to value(article.language.value)
        )
    }

    fun storyIdOf(payload: Map<String, Value>): StoryId {
        val raw = payload[STORY_ID]?.takeIf { it.hasStringValue() }?.stringValue
            ?: throw IllegalStateException("payload에 $STORY_ID 가 없습니다")

        return StoryId.of(UUID.fromString(raw))
    }
}
