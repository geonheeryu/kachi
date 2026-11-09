package me.rgunny.kachi.e2e.support

import java.time.Instant

/**
 * [TestGoogleNewsServer]가 RSS item 하나로 내보내는 기사.
 */
data class TestNewsArticle(
    val title: String,
    val url: String,
    val publisher: String,
    val publishedAt: Instant
)
