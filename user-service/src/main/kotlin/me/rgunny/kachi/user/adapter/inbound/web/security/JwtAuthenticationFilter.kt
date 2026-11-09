package me.rgunny.kachi.user.adapter.inbound.web.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.rgunny.kachi.user.application.port.inbound.auth.AuthenticateAccessTokenUseCase
import me.rgunny.kachi.user.domain.UserRole
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val authenticateAccessTokenUseCase: AuthenticateAccessTokenUseCase
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // 1. Authorization header에서 Bearer token을 꺼낸다.
        val token = resolveBearerToken(request)

        // 2. 아직 인증되지 않은 요청이면 access token으로 SecurityContext를 구성한다.
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            authenticate(token)
        }

        // 3. 인증 여부와 관계없이 다음 필터로 요청을 넘긴다.
        filterChain.doFilter(request, response)
    }

    private fun authenticate(token: String) {
        // 1. 유효한 access token만 인증 대상으로 본다.
        val authenticated = authenticateAccessTokenUseCase.authenticate(token) ?: return

        // 2. Controller와 application layer에서 사용할 인증 사용자 식별자만 principal에 담는다.
        val principal = AuthenticatedUser(
            userId = authenticated.userId,
            role = authenticated.role
        )
        val authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            listOf(SimpleGrantedAuthority(authenticated.role.authority()))
        )

        SecurityContextHolder.getContext().authentication = authentication
    }

    private fun resolveBearerToken(request: HttpServletRequest): String? {
        val authorization = request.getHeader(AUTHORIZATION_HEADER) ?: return null

        if (!authorization.startsWith(BEARER_PREFIX)) {
            return null
        }

        return authorization.removePrefix(BEARER_PREFIX).trim().ifBlank { null }
    }

    private fun UserRole.authority(): String = "ROLE_$name"

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
