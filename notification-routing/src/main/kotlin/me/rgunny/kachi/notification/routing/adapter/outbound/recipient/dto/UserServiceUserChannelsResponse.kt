package me.rgunny.kachi.notification.routing.adapter.outbound.recipient.dto

/**
 * 역할별 수신자 조회 응답 한 건.
 *
 * channels는 그 사용자가 받을 수 있는 채널이며 user-service의 enum 이름이다.
 */
data class UserServiceUserChannelsResponse(
    val userId: String,
    val channels: List<String>,
)
