package me.rgunny.kachi.user.config

import me.rgunny.kachi.user.adapter.inbound.web.AuthController
import me.rgunny.kachi.user.adapter.inbound.web.ChannelBindingController
import me.rgunny.kachi.user.adapter.inbound.web.InternalChannelBindingController
import me.rgunny.kachi.user.adapter.inbound.web.InternalSubscriptionController
import me.rgunny.kachi.user.adapter.inbound.web.InternalUserController
import me.rgunny.kachi.user.adapter.inbound.web.SubscriptionController
import me.rgunny.kachi.user.adapter.inbound.web.UserController
import me.rgunny.kachi.user.adapter.inbound.web.fake.WebMvcFakeOAuth2Config
import me.rgunny.kachi.user.adapter.inbound.web.fake.WebMvcFakeUseCaseConfig
import me.rgunny.kachi.user.adapter.outbound.auth.JwtTokenProvider
import me.rgunny.kachi.user.application.port.inbound.auth.AuthenticateAccessTokenUseCase
import me.rgunny.kachi.user.application.service.AccessTokenAuthenticationService
import me.rgunny.kachi.user.domain.SubscriptionId
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Clock
import java.time.Duration
import java.util.UUID

@WebMvcTest(
    controllers = [
        AuthController::class,
        UserController::class,
        SubscriptionController::class,
        ChannelBindingController::class,
        InternalChannelBindingController::class,
        InternalSubscriptionController::class,
        InternalUserController::class
    ]
)
@AutoConfigureMockMvc
@ImportAutoConfiguration(
    SecurityAutoConfiguration::class,
    ServletWebSecurityAutoConfiguration::class,
    SecurityFilterAutoConfiguration::class
)
@Import(
    ApiVersionConfig::class,
    SecurityConfig::class,
    SecurityConfigTest.JwtTestConfig::class,
    WebMvcFakeOAuth2Config::class,
    WebMvcFakeUseCaseConfig::class
)
@DisplayName("SecurityConfig")
class SecurityConfigTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtTokenProvider: JwtTokenProvider
) {

    @Nested
    @DisplayName("permit all")
    inner class PermitAll {

        @Test
        @DisplayName("사용자 등록 API는 인증 없이 접근할 수 있다")
        fun permitRegisterUserApi() {
            mockMvc.post("/api/v1/users") {
                contentType = MediaType.APPLICATION_JSON
                content = registerUserBody()
            }.andExpect {
                status { isCreated() }
            }
        }

        @Test
        @DisplayName("토큰 갱신 API는 인증 없이 접근할 수 있다")
        fun permitRefreshTokenApi() {
            mockMvc.post("/api/v1/auth/token/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = refreshTokenBody()
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("로그아웃 API는 인증 없이 접근할 수 있다")
        fun permitLogoutApi() {
            mockMvc.post("/api/v1/auth/logout") {
                contentType = MediaType.APPLICATION_JSON
                content = refreshTokenBody()
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("Telegram 연결 internal API는 인증 없이 접근할 수 있다")
        fun permitTelegramLinkInternalApi() {
            mockMvc.post("/api/v1/internal/channel-bindings/telegram/link") {
                contentType = MediaType.APPLICATION_JSON
                content = telegramLinkBody()
            }.andExpect {
                status { isNoContent() }
            }
        }

        @Test
        @DisplayName("구독 조회 internal API는 인증 없이 접근할 수 있다")
        fun permitSubscriptionsInternalApi() {
            mockMvc.get("/api/v1/internal/subscriptions") {
                param("keyword", "tesla")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("역할별 수신자 조회 internal API는 인증 없이 접근할 수 있다")
        fun permitUsersByRoleInternalApi() {
            mockMvc.get("/api/v1/internal/users") {
                param("role", "ADMIN")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("채널 바인딩 조회 internal API는 인증 없이 접근할 수 있다")
        fun permitChannelBindingInternalApi() {
            mockMvc.get("/api/v1/internal/users/${UUID.randomUUID()}/channel-bindings/SLACK") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("OAuth2 인증 시작 endpoint는 인증 없이 provider로 리다이렉트한다")
        fun permitOAuth2AuthorizationApi() {
            mockMvc.get("/oauth2/authorization/google") {
                accept = MediaType.TEXT_HTML
            }.andExpect {
                status { is3xxRedirection() }
                header { exists("Location") }
                cookie { exists("oauth2_authorization_request") }
            }
        }

    }

    @Nested
    @DisplayName("authenticated")
    inner class Authenticated {

        @Test
        @DisplayName("내 정보 조회 API는 인증을 요구한다")
        fun requireAuthenticationForGetMeApi() {
            mockMvc.get("/api/v1/me") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("내 정보 조회 API는 access token으로 접근할 수 있다")
        fun permitGetMeApiWithAccessToken() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.get("/api/v1/me") {
                header("Authorization", "Bearer ${token.value}")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("내 정보 조회 API는 refresh token으로 접근할 수 없다")
        fun rejectGetMeApiWithRefreshToken() {
            val token = jwtTokenProvider.createRefreshToken(UserId.newId())

            mockMvc.get("/api/v1/me") {
                header("Authorization", "Bearer ${token.value}")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("내 탈퇴 API는 인증을 요구한다")
        fun requireAuthenticationForDeactivateMeApi() {
            mockMvc.delete("/api/v1/me") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("내 탈퇴 API는 access token으로 접근할 수 있다")
        fun permitDeactivateMeApiWithAccessToken() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.delete("/api/v1/me") {
                header("Authorization", "Bearer ${token.value}")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("인증 사용자 관심 키워드 등록 API는 인증을 요구한다")
        fun requireAuthenticationForRegisterMyKeywordApi() {
            mockMvc.post("/api/v1/me/keywords") {
                contentType = MediaType.APPLICATION_JSON
                content = registerSubscriptionBody()
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("인증 사용자 관심 키워드 등록 API는 access token으로 접근할 수 있다")
        fun permitRegisterMyKeywordApiWithAccessToken() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.post("/api/v1/me/keywords") {
                header("Authorization", "Bearer ${token.value}")
                contentType = MediaType.APPLICATION_JSON
                content = registerSubscriptionBody()
            }.andExpect {
                status { isCreated() }
            }
        }

        @Test
        @DisplayName("인증 사용자 관심 키워드 목록 조회 API는 인증을 요구한다")
        fun requireAuthenticationForListMyKeywordsApi() {
            mockMvc.get("/api/v1/me/keywords") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("인증 사용자 관심 키워드 목록 조회 API는 access token으로 접근할 수 있다")
        fun permitListMyKeywordsApiWithAccessToken() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.get("/api/v1/me/keywords") {
                header("Authorization", "Bearer ${token.value}")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("관심 키워드 수정 API는 인증을 요구한다")
        fun requireAuthenticationForUpdateKeywordApi() {
            mockMvc.patch("/api/v1/me/keywords/${SubscriptionId.newId().value}") {
                contentType = MediaType.APPLICATION_JSON
                content = updateSubscriptionBody()
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("관심 키워드 수정 API는 access token으로 접근할 수 있다")
        fun permitUpdateKeywordApiWithAccessToken() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.patch("/api/v1/me/keywords/${SubscriptionId.newId().value}") {
                header("Authorization", "Bearer ${token.value}")
                contentType = MediaType.APPLICATION_JSON
                content = updateSubscriptionBody()
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("채널 바인딩 목록 API는 인증을 요구한다")
        fun requireAuthenticationForListChannelBindingsApi() {
            mockMvc.get("/api/v1/me/channel-bindings") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("채널 바인딩 등록 API는 인증을 요구한다")
        fun requireAuthenticationForBindChannelApi() {
            mockMvc.put("/api/v1/me/channel-bindings/SLACK") {
                contentType = MediaType.APPLICATION_JSON
                content = webhookBindingBody()
            }.andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("채널 바인딩 등록 API는 access token으로 접근할 수 있다")
        fun permitBindChannelApiWithAccessToken() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.put("/api/v1/me/channel-bindings/SLACK") {
                header("Authorization", "Bearer ${token.value}")
                contentType = MediaType.APPLICATION_JSON
                content = webhookBindingBody()
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("Telegram 바인딩은 본문 없이 access token으로 접근할 수 있다")
        fun permitTelegramBindWithoutBody() {
            val token = jwtTokenProvider.createAccessToken(UserId.newId(), UserRole.USER)

            mockMvc.put("/api/v1/me/channel-bindings/TELEGRAM") {
                header("Authorization", "Bearer ${token.value}")
            }.andExpect {
                status { isOk() }
            }
        }

        @Test
        @DisplayName("채널 바인딩 해지 API는 인증을 요구한다")
        fun requireAuthenticationForRevokeChannelBindingApi() {
            mockMvc.delete("/api/v1/me/channel-bindings/SLACK").andExpect {
                status { isForbidden() }
            }
        }

        @Test
        @DisplayName("허용하지 않은 API는 인증을 요구한다")
        fun requireAuthenticationForOtherApis() {
            mockMvc.get("/api/v1/unknown") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isForbidden() }
            }
        }
    }

    private fun registerUserBody(): String {
        return """
            {
              "email": "rgunny@kachi.com",
              "nickname": "rgunny",
              "authProvider": "GOOGLE"
            }
        """.trimIndent()
    }

    private fun registerSubscriptionBody(): String {
        return """
            {
              "name": "Trump",
              "channels": ["SLACK"]
            }
        """.trimIndent()
    }

    private fun updateSubscriptionBody(): String {
        return """
            {
              "enabled": true
            }
        """.trimIndent()
    }

    private fun webhookBindingBody(): String {
        return """
            {
              "webhookUrl": "https://hooks.slack.com/services/T000/B000/XXXX"
            }
        """.trimIndent()
    }

    private fun telegramLinkBody(): String {
        return """
            {
              "token": "token",
              "chatId": "123456789"
            }
        """.trimIndent()
    }

    private fun refreshTokenBody(): String {
        return """
            {
              "refreshToken": "refresh-token"
            }
        """.trimIndent()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class JwtTestConfig {

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider {
            // SecurityConfig slice test는 properties binding 대신 필터에 필요한 provider만 직접 제공한다.
            return JwtTokenProvider(
                secret = "morCcncONBqndWq56eP75u8LgYDg+HLWlfhqugyfDA4=",
                accessTokenTtl = Duration.ofMinutes(15),
                refreshTokenTtl = Duration.ofDays(14),
                clock = Clock.systemUTC()
            )
        }

        @Bean
        fun authenticateAccessTokenUseCase(jwtTokenProvider: JwtTokenProvider): AuthenticateAccessTokenUseCase {
            return AccessTokenAuthenticationService(jwtTokenProvider)
        }
    }
}
