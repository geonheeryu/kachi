package me.rgunny.kachi.e2e.support

import java.util.UUID

/**
 * 테스트 하나의 기사 제목에 심는 story 표지.
 *
 * TEI stub은 이 값으로 임베딩을 정하고 LLM stub은 이 값으로 응답을 고른다.
 * 형식은 `e2e-story-` 뒤에 소문자 16진수 12자다.
 */
@JvmInline
value class StoryMarker private constructor(val value: String) {

    companion object {
        private val PATTERN = Regex("e2e-story-[0-9a-f]{12}")

        fun random(): StoryMarker = StoryMarker("e2e-story-${UUID.randomUUID().toString().replace("-", "").take(12)}")

        /** [text]에 처음 나오는 표지. 없으면 null이다. */
        fun findIn(text: String): StoryMarker? = PATTERN.find(text)?.let { StoryMarker(it.value) }
    }
}
