package me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model

/**
 * 구독 수신자 조회의 한 페이지.
 *
 * nextCursor가 null이면 마지막 페이지이고, 값이 있으면 non-blank다.
 */
data class RecipientPage(
    val recipients: List<SubscribedRecipient>,
    val nextCursor: String?,
) {
    init {
        require(nextCursor == null || nextCursor.isNotBlank()) { "nextCursor must not be blank" }
    }
}
