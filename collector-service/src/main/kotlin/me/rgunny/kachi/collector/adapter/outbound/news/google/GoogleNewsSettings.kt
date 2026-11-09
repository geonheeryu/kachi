package me.rgunny.kachi.collector.adapter.outbound.news.google

/**
 * Google News RSS provider가 보는 검색 설정.
 *
 * `kachi.collector.providers.google`
 */
data class GoogleNewsSettings(
    val rssSearchPath: String,
    val languageCode: String,
    val countryCode: String
)
