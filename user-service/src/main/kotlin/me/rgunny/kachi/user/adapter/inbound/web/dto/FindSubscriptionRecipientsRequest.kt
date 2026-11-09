package me.rgunny.kachi.user.adapter.inbound.web.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscriptionRecipientsQuery

/**
 * 구독 수신자 조회 요청.
 *
 * [keywords]는 원문 1~100개이며 null과 blank를 허용하지 않는다.
 * [cursor]는 앞 페이지의 `nextCursor`(UUID 문자열)이고 첫 페이지면 null이다.
 * [limit]은 한 페이지의 사용자 수 1~100이며 생략하면 null이고 [DEFAULT_LIMIT]으로 읽는다.
 */
data class FindSubscriptionRecipientsRequest(

    @field:Size(min = 1, max = FindSubscriptionRecipientsQuery.MAX_KEYWORDS)
    val keywords: List<String>,

    @field:Pattern(regexp = UUID_PATTERN)
    val cursor: String?,

    @field:Min(1)
    @field:Max(FindSubscriptionRecipientsQuery.MAX_LIMIT.toLong())
    val limit: Int?
) {

    /** JSON 배열 원소의 null은 Kotlin 타입과 무관하게 들어올 수 있어 원소마다 검사한다. */
    @get:AssertTrue(message = "keywords에 null이나 blank 값을 넣을 수 없습니다")
    val isEveryKeywordPresent: Boolean
        get() = keywords.none { it.isNullOrBlank() }

    fun limitOrDefault(): Int = limit ?: DEFAULT_LIMIT

    companion object {
        const val DEFAULT_LIMIT = FindSubscriptionRecipientsQuery.MAX_LIMIT

        /** 하이픈을 포함한 36자 UUID 표기 */
        const val UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    }
}
