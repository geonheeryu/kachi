package me.rgunny.kachi.notification.routing.domain

/**
 * routing job이 fan-out하는 이벤트의 종류. 누구에게 보내는지가 아니라 어떤 이벤트인지다.
 */
enum class RoutingJobKind {
    SUMMARY,      // 요약 생성 -> 키워드 구독자 x 채널
    QUARANTINE,   // 키워드 격리 -> 관리자 x 채널
}
