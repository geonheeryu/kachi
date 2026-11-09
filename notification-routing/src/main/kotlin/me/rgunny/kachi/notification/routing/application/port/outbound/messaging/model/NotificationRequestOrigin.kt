package me.rgunny.kachi.notification.routing.application.port.outbound.messaging.model

/**
 * 알림 요청의 출처.
 *
 * 요약 알림은 모든 값을, 격리 알림은 storyId와 userId만 가진다.
 * summaryId와 version은 함께 있거나 함께 없고, keywords는 수신자가 구독한 canonicalKey다.
 */
data class NotificationRequestOrigin(
    val summaryId: String?,
    val storyId: String,
    val version: Long?,
    val keywords: List<String>,
    val userId: String,
) {
    init {
        require(summaryId == null || summaryId.isNotBlank()) { "summaryId must not be blank" }
        require(storyId.isNotBlank()) { "storyId must not be blank" }
        require((summaryId == null) == (version == null)) { "summaryId and version must be present together" }
        require(version == null || version >= 1) { "version must be positive" }
        require(keywords.none { it.isBlank() }) { "keywords must not contain blank" }
        require(userId.isNotBlank()) { "userId must not be blank" }
    }
}
