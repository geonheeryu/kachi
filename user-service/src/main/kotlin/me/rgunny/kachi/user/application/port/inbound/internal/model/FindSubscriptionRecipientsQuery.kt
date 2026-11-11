package me.rgunny.kachi.user.application.port.inbound.internal.model

import me.rgunny.kachi.user.domain.UserId

/**
 * 수신자를 찾을 키워드와 페이지 위치.
 *
 * [keywords]는 원문이며 1~[MAX_KEYWORDS]개다.
 * [cursor]는 앞 페이지의 마지막 사용자이고 첫 페이지면 null이다.
 * [limit]은 한 페이지의 사용자 수이며 1~[MAX_LIMIT]이다.
 */
data class FindSubscriptionRecipientsQuery(
    val keywords: List<String>,
    val cursor: UserId?,
    val limit: Int
) {
    init {
        require(keywords.size in 1..MAX_KEYWORDS) { "키워드는 1~${MAX_KEYWORDS}개여야 합니다" }
        require(limit in 1..MAX_LIMIT) { "limit은 1~${MAX_LIMIT}이어야 합니다" }
    }

    companion object {
        const val MAX_KEYWORDS = 100
        const val MAX_LIMIT = 100
    }
}
