package me.rgunny.kachi.notification.routing.adapter.outbound.recipient

import java.time.Duration

/**
 * user-service 수신자 조회 클라이언트가 보는 호출 설정.
 *
 * `kachi.notification.routing.user-service`
 * pageSize는 구독 수신자 조회 한 페이지의 사용자 수다.
 */
data class UserServiceRecipientReaderSettings(
    val recipientsSearchPath: String,
    val pageSize: Int,
    val usersPath: String,
    val timeout: Duration,
)
