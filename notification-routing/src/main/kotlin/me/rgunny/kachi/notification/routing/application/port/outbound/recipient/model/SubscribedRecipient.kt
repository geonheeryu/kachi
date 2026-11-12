package me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model

import me.rgunny.kachi.notification.contract.NotificationChannel

/**
 * 구독 수신자 1명의 채널 1개와 그 채널로 구독한 키워드.
 *
 * recipientId는 사용자 id이며 주소는 갖지 않는다.
 * keywords는 비어 있지 않고 canonicalKey 오름차순이며 중복이 없다.
 */
data class SubscribedRecipient(
    val recipientId: String,
    val channel: NotificationChannel,
    val keywords: List<SubscribedKeyword>,
) {
    init {
        require(recipientId.isNotBlank()) { "recipientId must not be blank" }
        require(keywords.isNotEmpty()) { "keywords must not be empty" }
        require(keywords.zipWithNext().all { (previous, next) -> previous.canonicalKey < next.canonicalKey }) {
            "keywords must be strictly ascending by canonicalKey"
        }
    }
}
