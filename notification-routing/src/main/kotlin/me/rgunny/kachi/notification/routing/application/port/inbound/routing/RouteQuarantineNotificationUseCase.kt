package me.rgunny.kachi.notification.routing.application.port.inbound.routing

import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteNotificationResult
import me.rgunny.kachi.notification.routing.application.port.inbound.routing.model.RouteQuarantineCommand

/**
 * story 격리 이벤트를 관리자 x 채널로 라우팅하는 유스케이스.
 *
 * 대상 전체를 한 페이지로 발행한다는 점 외에는 요약 라우팅과 같은 규칙을 따른다.
 */
interface RouteQuarantineNotificationUseCase {

    suspend fun routeQuarantine(command: RouteQuarantineCommand): RouteNotificationResult
}
