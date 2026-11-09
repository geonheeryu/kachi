package me.rgunny.kachi.collector.adapter.outbound.news

import org.springframework.web.util.HtmlUtils

/**
 * provider가 HTML로 준 제목·설명을 평문으로 바꾸는 변환기.
 *
 * entity를 풀고 태그를 지운 뒤 공백을 정리한다. `&nbsp;`가 풀린 U+00A0도 공백으로 본다.
 */
object HtmlText {
    private val TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")

    fun toPlain(html: String): String {
        return HtmlUtils.htmlUnescape(html)
            .replace(TAG, "")
            .replace(WHITESPACE, " ")
            .trim()
    }
}
