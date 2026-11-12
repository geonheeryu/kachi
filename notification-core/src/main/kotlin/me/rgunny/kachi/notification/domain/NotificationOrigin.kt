package me.rgunny.kachi.notification.domain

/**
 * 알림이 어느 story 요약·구독 키워드·사용자에서 비롯됐는지 나타내는 출처.
 *
 * 요청 계약에 출처가 실려 온 알림만 값을 가지며, 출처 없이 들어온 알림은 [NONE]이다.
 * 발송 이력을 사용자·story 축으로 모으는 조회의 원천이고 발송 자체에는 관여하지 않는다.
 *
 * @property summaryId 알림 본문의 원천인 요약 식별자.
 * @property storyId 요약이 속한 story 식별자.
 * @property version [storyId] story의 요약 버전(1 이상).
 * @property keywords story의 키워드 중 수신자가 구독한 canonicalKey 목록(없으면 빈 목록).
 * @property userId 알림을 받는 사용자 식별자.
 */
data class NotificationOrigin(
    val summaryId: String?,
    val storyId: String?,
    val version: Long?,
    val keywords: List<String>,
    val userId: String?,
) {
    init {
        require(summaryId == null || summaryId.isNotBlank()) { "summaryId must not be blank" }
        require(storyId == null || storyId.isNotBlank()) { "storyId must not be blank" }
        require(version == null || version >= 1) { "version must be positive: $version" }
        require(keywords.none { it.isBlank() }) { "keywords must not contain blank" }
        require(userId == null || userId.isNotBlank()) { "userId must not be blank" }
    }

    companion object {
        val NONE = NotificationOrigin(
            summaryId = null,
            storyId = null,
            version = null,
            keywords = emptyList(),
            userId = null,
        )
    }
}
