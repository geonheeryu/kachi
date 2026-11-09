package me.rgunny.kachi.user.application.service

import me.rgunny.kachi.user.application.port.outbound.auth.TokenPort
import me.rgunny.kachi.user.application.token.IssuedToken
import me.rgunny.kachi.user.application.token.ParsedToken
import me.rgunny.kachi.user.application.token.TokenType
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("AccessTokenAuthenticationService")
class AccessTokenAuthenticationServiceTest {
    private val userId = UserId.newId()

    @Test
    @DisplayName("유효한 access token이면 사용자와 역할을 돌려준다")
    fun authenticateAccessToken() {
        val service = AccessTokenAuthenticationService(
            FakeTokenPort(tokenType = TokenType.ACCESS, role = UserRole.USER)
        )

        val authentication = service.authenticate("access-token")

        assertNotNull(authentication)
        assertEquals(userId, authentication.userId)
        assertEquals(UserRole.USER, authentication.role)
    }

    @Test
    @DisplayName("유효하지 않은 token이면 null이고 token을 파싱하지 않는다")
    fun rejectInvalidToken() {
        val tokenPort = FakeTokenPort(valid = false, tokenType = TokenType.ACCESS, role = UserRole.USER)
        val service = AccessTokenAuthenticationService(tokenPort)

        val authentication = service.authenticate("invalid-token")

        assertNull(authentication)
        assertNull(tokenPort.parsedToken)
    }

    @Test
    @DisplayName("refresh token이면 null이다")
    fun rejectRefreshToken() {
        val service = AccessTokenAuthenticationService(
            FakeTokenPort(tokenType = TokenType.REFRESH, role = null)
        )

        assertNull(service.authenticate("refresh-token"))
    }

    @Test
    @DisplayName("role이 없는 access token이면 null이다")
    fun rejectAccessTokenWithoutRole() {
        val service = AccessTokenAuthenticationService(
            FakeTokenPort(tokenType = TokenType.ACCESS, role = null)
        )

        assertNull(service.authenticate("access-token"))
    }

    /** 검증 결과와 claim을 고정해 돌려주는 token 포트 fake. */
    private inner class FakeTokenPort(
        private val valid: Boolean = true,
        private val tokenType: TokenType,
        private val role: UserRole?
    ) : TokenPort {
        var parsedToken: String? = null

        override fun issueAccessToken(userId: UserId, role: UserRole): IssuedToken {
            throw UnsupportedOperationException("인증 판정은 token을 발급하지 않는다")
        }

        override fun issueRefreshToken(userId: UserId): IssuedToken {
            throw UnsupportedOperationException("인증 판정은 token을 발급하지 않는다")
        }

        override fun parseToken(token: String): ParsedToken {
            parsedToken = token

            return ParsedToken(
                id = "token-id",
                userId = userId,
                type = tokenType,
                role = role
            )
        }

        override fun isValid(token: String): Boolean {
            return valid
        }
    }
}
