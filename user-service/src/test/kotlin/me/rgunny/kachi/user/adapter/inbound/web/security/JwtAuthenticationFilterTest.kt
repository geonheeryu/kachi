package me.rgunny.kachi.user.adapter.inbound.web.security

import me.rgunny.kachi.user.application.port.inbound.auth.AuthenticateAccessTokenUseCase
import me.rgunny.kachi.user.application.port.inbound.auth.model.AccessTokenAuthentication
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {
    private val userId = UserId.newId()
    private val authenticateAccessTokenUseCase = FakeAuthenticateAccessTokenUseCase(
        mapOf(ACCESS_TOKEN to AccessTokenAuthentication(userId = userId, role = UserRole.USER))
    )
    private val filter = JwtAuthenticationFilter(authenticateAccessTokenUseCase)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Nested
    @DisplayName("doFilterInternal()")
    inner class DoFilterInternal {

        @Test
        @DisplayName("인증된 access token이면 인증 정보를 저장한다")
        fun authenticateWithAccessToken() {
            val request = requestWithBearerToken(ACCESS_TOKEN)

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            val authentication = SecurityContextHolder.getContext().authentication
            assertNotNull(authentication)
            val principal = assertIs<AuthenticatedUser>(authentication.principal)
            assertEquals(userId, principal.userId)
            assertEquals(UserRole.USER, principal.role)
            assertTrue(authentication.isAuthenticated)
            assertEquals("ROLE_USER", authentication.authorities.first().authority)
        }

        @Test
        @DisplayName("인증되지 않는 token이면 인증 정보로 저장하지 않는다")
        fun skipUnauthenticatedToken() {
            val request = requestWithBearerToken("unauthenticated-token")

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertNull(SecurityContextHolder.getContext().authentication)
            assertEquals("unauthenticated-token", authenticateAccessTokenUseCase.requestedToken)
        }

        @Test
        @DisplayName("Bearer token이 없으면 인증 정보로 저장하지 않는다")
        fun skipMissingBearerToken() {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())

            assertNull(SecurityContextHolder.getContext().authentication)
            assertNull(authenticateAccessTokenUseCase.requestedToken)
        }

        @Test
        @DisplayName("기존 인증 정보가 있으면 덮어쓰지 않는다")
        fun keepExistingAuthentication() {
            val existingAuthentication = TestingAuthenticationToken(
                "existing",
                "credentials",
                "ROLE_ADMIN"
            )
            SecurityContextHolder.getContext().authentication = existingAuthentication
            val request = requestWithBearerToken(ACCESS_TOKEN)

            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

            assertEquals(existingAuthentication, SecurityContextHolder.getContext().authentication)
            assertFalse(SecurityContextHolder.getContext().authentication?.principal is AuthenticatedUser)
        }
    }

    private fun requestWithBearerToken(token: String): MockHttpServletRequest {
        return MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $token")
        }
    }

    /** token 문자열별로 정해 둔 인증 결과를 돌려주는 유스케이스 fake. */
    private class FakeAuthenticateAccessTokenUseCase(
        private val authentications: Map<String, AccessTokenAuthentication>
    ) : AuthenticateAccessTokenUseCase {
        var requestedToken: String? = null

        override fun authenticate(accessToken: String): AccessTokenAuthentication? {
            requestedToken = accessToken

            return authentications[accessToken]
        }
    }

    companion object {
        private const val ACCESS_TOKEN = "access-token"
    }
}
