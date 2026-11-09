package me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model

/**
 * 수신자가 구독한 키워드 1건.
 *
 * canonicalKey는 정규화된 키워드이고 displayName은 사용자에게 보이는 이름이며 둘 다 non-blank다.
 */
data class SubscribedKeyword(
    val canonicalKey: String,
    val displayName: String,
) {
    init {
        require(canonicalKey.isNotBlank()) { "canonicalKey must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }
}
