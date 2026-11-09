package me.rgunny.kachi.e2e.support

import java.util.Locale
import java.util.UUID

/**
 * 테스트 하나가 구독하는 키워드의 원문과 user-service가 정규화한 canonicalKey.
 *
 * 원문은 ASCII 영숫자와 가운데 하이픈뿐이라 canonicalKey는 원문의 소문자다.
 */
data class TestKeyword(val displayName: String, val canonicalKey: String) {

    companion object {

        /** `E2E-{label}-{8자}` 형식의 고유 키워드. [label]은 ASCII 영숫자다. */
        fun unique(label: String): TestKeyword {
            val displayName = "E2E-$label-${UUID.randomUUID().toString().take(8)}"
            return TestKeyword(displayName = displayName, canonicalKey = displayName.lowercase(Locale.ROOT))
        }
    }
}
