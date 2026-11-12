package me.rgunny.kachi.notification.routing.application.port.inbound.routing

import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteNotificationResult
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteSummaryCommand

/**
 * story 요약 버전을 그 story의 키워드를 구독한 수신자 x 채널로 라우팅하는 유스케이스.
 *
 * 책임:
 * - eventKey(`{storyId}:{version}`) 기준으로 한 번만 라우팅한다 (RoutingJob)
 * - 구독 수신자를 페이지로 읽어 수신자마다 결정적 requestId로 알림 요청을 발행한다
 * - 중단됐던 job은 저장된 cursor부터 이어서 진행한다
 */
interface RouteSummaryNotificationUseCase {

    suspend fun routeSummary(command: RouteSummaryCommand): RouteNotificationResult
}
