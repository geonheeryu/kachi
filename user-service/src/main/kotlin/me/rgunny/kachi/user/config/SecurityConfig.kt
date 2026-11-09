package me.rgunny.kachi.user.config

import me.rgunny.kachi.user.adapter.inbound.web.ApiPaths
import me.rgunny.kachi.user.adapter.inbound.web.oauth.CustomOAuth2UserService
import me.rgunny.kachi.user.adapter.inbound.web.oauth.OAuth2AuthenticationSuccessHandler
import me.rgunny.kachi.user.adapter.inbound.web.security.JwtAuthenticationFilter
import me.rgunny.kachi.user.application.port.inbound.auth.AuthenticateAccessTokenUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authenticateAccessTokenUseCase: AuthenticateAccessTokenUseCase,
        customOAuth2UserService: CustomOAuth2UserService,
        oAuth2AuthenticationSuccessHandler: OAuth2AuthenticationSuccessHandler,
        oAuth2AuthorizationRequestRepository: AuthorizationRequestRepository<OAuth2AuthorizationRequest>
    ): SecurityFilterChain {
        http
            // JWT 기반 stateless API로 갈 예정이므로 서버 세션과 CSRF 토큰을 사용하지 않는다.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // API 서버에서는 브라우저 기본 로그인 화면과 Basic 인증을 노출하지 않는다.
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .exceptionHandling { exceptions ->
                // OAuth2 Login 기본 entry point가 API 미인증 요청을 redirect로 바꾸지 않게 한다.
                exceptions.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.FORBIDDEN))
            }
            .authorizeHttpRequests { requests ->
                requests
                    // Actuator 헬스체크 endpoint는 인증 없이 접근할 수 있게 한다.
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // OAuth2 provider 이동과 callback endpoint는 Spring Security OAuth2 filter가 처리한다.
                    .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                    // 사용자 등록과 refresh token 기반 인증 API만 공개한다.
                    .requestMatchers(HttpMethod.POST, ApiPaths.V1_AUTH_TOKEN_REFRESH).permitAll()
                    .requestMatchers(HttpMethod.POST, ApiPaths.V1_AUTH_LOGOUT).permitAll()
                    .requestMatchers(HttpMethod.POST, ApiPaths.V1_USERS).permitAll()
                    // internal API는 같은 호스트의 서비스끼리만 호출하므로 인증 없이 연다 (ADR 026).
                    .requestMatchers(ApiPaths.V1_INTERNAL_ANY).permitAll()
                    // 그 외 API는 access token 인증을 요구한다.
                    .anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .authorizationEndpoint { authorization ->
                        // stateless API 구조에 맞춰 OAuth2 authorization request를 세션 대신 쿠키에 저장한다.
                        authorization.authorizationRequestRepository(oAuth2AuthorizationRequestRepository)
                    }
                    .userInfoEndpoint { userInfo ->
                        // provider별 attributes를 내부 사용자 식별자로 변환한다.
                        userInfo.userService(customOAuth2UserService)
                    }
                    // OAuth2 인증 성공 후 일반 로그인과 동일하게 JWT access/refresh token을 발급한다.
                    .successHandler(oAuth2AuthenticationSuccessHandler)
            }
            .addFilterBefore(
                // Bearer token을 SecurityContext 인증 정보로 변환한다.
                JwtAuthenticationFilter(authenticateAccessTokenUseCase),
                UsernamePasswordAuthenticationFilter::class.java
            )

        return http.build()
    }
}
