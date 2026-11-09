package me.rgunny.kachi.collector.adapter.outbound.news.google

/**
 * Google News RSS `<item>` 하나. 태그 텍스트를 그대로 담고, 값 정리는 provider가 한다.
 */
data class GoogleRssItem(
    val title: String,
    val link: String,
    val description: String?,
    val pubDate: String?,
    val sourceName: String?
)
