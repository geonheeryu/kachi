package me.rgunny.kachi.user.config

import me.rgunny.kachi.user.adapter.outbound.auth.JwtTokenProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig {

    @Bean
    fun jwtTokenProvider(
        jwtProperties: JwtProperties,
        clock: Clock
    ): JwtTokenProvider {
        return JwtTokenProvider(
            secret = jwtProperties.secret,
            accessTokenTtl = jwtProperties.accessTokenTtl,
            refreshTokenTtl = jwtProperties.refreshTokenTtl,
            clock = clock
        )
    }
}
