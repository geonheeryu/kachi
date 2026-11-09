package me.rgunny.kachi.notification.contract

/**
 * 알림 요청이 어느 story 요약·키워드·사용자에서 비롯됐는지 나타내는 출처.
 *
 * 라우팅이 만든 요청만 값을 채우고, 그 밖의 producer는 생략한다.
 * 모든 값은 선택이며 주소는 싣지 않는다.
 *
 * @property summaryId 알림 본문의 원천인 요약 식별자.
 * @property storyId 요약이 속한 story 식별자.
 * @property version [storyId] story의 요약 버전.
 * @property keywords story의 키워드 중 수신자가 구독한 canonicalKey 목록(없으면 빈 목록).
 * @property userId 알림을 받는 사용자 식별자.
 */
data class NotificationRequestedOrigin(
    val summaryId: String? = null,
    val storyId: String? = null,
    val version: Long? = null,
    val keywords: List<String> = emptyList(),
    val userId: String? = null,
)
