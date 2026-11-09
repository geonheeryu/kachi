package me.rgunny.kachi.ai.contract

/**
 * 격리 이벤트에서 사용하는 실패 원인 계약.
 */
enum class AiFailureReason {
    TIMEOUT,
    RATE_LIMITED,
    CLIENT_ERROR,
    SERVER_ERROR,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    PROVIDER_UNAVAILABLE,
    UNKNOWN
}
