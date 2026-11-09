package me.rgunny.kachi.user.domain

import java.text.Normalizer
import java.util.Locale

/**
 * 키워드 원문을 canonical 문자열로 정규화한다.
 *
 * NFKC -> trim -> 연속 공백 1개 -> casefold(Locale.ROOT) -> 양끝 구두점 제거(Unicode P* 카테고리).
 * 기호(S*, 예: `$`, `+`)와 문자열 중간의 구두점(예: `space-x`의 하이픈)은 남긴다.
 */
object KeywordNormalizer {

    private val WHITESPACES = Regex("\\s+")

    /** 결과가 빈 문자열일 수 있다(구두점만 있는 입력). 거부는 [CanonicalKey]가 한다. */
    fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACES, " ")
            .lowercase(Locale.ROOT)

        return folded.trim { isPunctuation(it) }.trim()
    }

    private fun isPunctuation(ch: Char): Boolean {
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
