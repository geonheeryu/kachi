package me.rgunny.kachi.notification.routing.application.service

import me.rgunny.kachi.notification.contract.NotificationChannel

/**
 * 라우팅한 알림의 requestId를 만든다.
 *
 * (이벤트, 대상)의 순수 함수라서 어느 지점에서 중단됐다 다시 라우팅해도 같은 값이 나오고,
 * 접수의 requestId 멱등이 중복 알림을 막는다.
 * 요약 requestId에는 키워드가 없으므로 story 한 버전에 수신자 x 채널당 하나다.
 */
object RoutingRequestId {

    fun forSummary(storyId: String, version: Long, recipientId: String, channel: NotificationChannel): String {
        return "sum:$storyId:$version:u:$recipientId:c:${channel.name}"
    }

    fun forQuarantine(eventKey: String, recipientId: String, channel: NotificationChannel): String {
        return "qrt:$eventKey:u:$recipientId:c:${channel.name}"
    }
}
