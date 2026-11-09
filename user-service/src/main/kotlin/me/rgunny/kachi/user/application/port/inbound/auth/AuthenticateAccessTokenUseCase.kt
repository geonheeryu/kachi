package me.rgunny.kachi.user.application.port.inbound.auth

import me.rgunny.kachi.user.application.port.inbound.auth.model.AccessTokenAuthentication

/** access token으로 요청 사용자를 식별하는 유스케이스 포트 */
interface AuthenticateAccessTokenUseCase {

    /** 유효한 access token이면 인증 결과를 반환하고, 아니면 null을 반환한다. */
    fun authenticate(accessToken: String): AccessTokenAuthentication?
}
