package me.rgunny.kachi.ai.domain.llm

/**
 * 앱이 LLM을 부르는 이유.
 *
 * 상수는 호출 포트의 메서드와 1:1이다.
 */
enum class LlmUse {
    STORY_SUMMARY,
    KEYWORD_EXPANSION
}
