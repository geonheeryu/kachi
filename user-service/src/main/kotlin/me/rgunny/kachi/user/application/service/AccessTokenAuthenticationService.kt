package me.rgunny.kachi.user.application.service

import me.rgunny.kachi.user.application.port.inbound.auth.AuthenticateAccessTokenUseCase
import me.rgunny.kachi.user.application.port.inbound.auth.model.AccessTokenAuthentication
import me.rgunny.kachi.user.application.port.outbound.auth.TokenPort
import me.rgunny.kachi.user.application.token.TokenType
import org.springframework.stereotype.Service

/**
 * access token을 검증해 요청 사용자를 식별하는 서비스.
 *
 * refresh token은 재발급 용도라 인증에 쓰지 않는다.
 */
@Service
class AccessTokenAuthenticationService(
    private val tokenPort: TokenPort
) : AuthenticateAccessTokenUseCase {

    override fun authenticate(accessToken: String): AccessTokenAuthentication? {
        // 1. 서명, 만료 시간, 필수 claim이 유효한 token만 인증 대상으로 본다.
        if (!tokenPort.isValid(accessToken)) {
            return null
        }

        // 2. API 인증에는 access token만 사용한다.
        val claims = tokenPort.parseToken(accessToken)
        val role = claims.role
        if (claims.type != TokenType.ACCESS || role == null) {
            return null
        }

        return AccessTokenAuthentication(
            userId = claims.userId,
            role = role
        )
    }
}
