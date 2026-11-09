package me.rgunny.kachi.collector.adapter.outbound.news.finnhub

/**
 * Finnhub News provider가 보는 조회 설정과 credential.
 *
 * `kachi.collector.providers.finnhub`
 */
data class FinnhubNewsSettings(
    val companyNewsPath: String,
    val apiKey: String,
    val lookbackDays: Long
)
