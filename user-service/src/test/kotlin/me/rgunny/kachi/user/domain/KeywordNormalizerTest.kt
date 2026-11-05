package me.rgunny.kachi.user.domain

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

@DisplayName("KeywordNormalizer")
class KeywordNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        value = [
            "'Ｔｅｓｌａ'|'tesla'",
            "'  NVIDIA  '|'nvidia'",
            "'space   x'|'space x'",
            "'Tesla'|'tesla'",
            "'테슬라.'|'테슬라'",
            "'(nvidia)'|'nvidia'",
            "'\"quoted\"'|'quoted'",
            "'SPACE-X'|'space-x'",
            "'\$TSLA'|'\$tsla'",
            "'C++'|'c++'",
            "'... '|''",
        ],
        delimiter = '|'
    )
    @DisplayName("NFKC, trim, 공백 축약, casefold, 양끝 구두점 제거를 순서대로 적용한다")
    fun normalize(raw: String, expected: String) {
        assertEquals(expected, KeywordNormalizer.normalize(raw))
    }
}
