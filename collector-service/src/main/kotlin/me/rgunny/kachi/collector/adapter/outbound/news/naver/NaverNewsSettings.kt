package me.rgunny.kachi.collector.adapter.outbound.news.naver

/**
 * Naver News Search provider가 보는 검색 설정과 credential.
 *
 * `kachi.collector.providers.naver`
 */
data class NaverNewsSettings(
    val newsSearchPath: String,
    val clientId: String,
    val clientSecret: String,
    val display: Int,
    val start: Int,
    val sort: String
)
