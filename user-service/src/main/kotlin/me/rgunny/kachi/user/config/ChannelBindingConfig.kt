package me.rgunny.kachi.user.config

import me.rgunny.kachi.user.adapter.outbound.binding.AesGcmAddressCipher
import me.rgunny.kachi.user.application.port.outbound.binding.AddressCipherPort
import me.rgunny.kachi.user.application.port.outbound.binding.ChannelBindingPersistencePort
import me.rgunny.kachi.user.application.service.ActiveUserValidator
import me.rgunny.kachi.user.application.service.ChannelBindingCommandService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 채널 바인딩 설정값을 필요한 빈에 옮긴다.
 * 서비스가 설정 타입을 직접 알지 않게 하기 위해서다.
 */
@Configuration
@EnableConfigurationProperties(ChannelBindingProperties::class, TelegramProperties::class)
class ChannelBindingConfig {

    @Bean
    fun addressCipherPort(channelBindingProperties: ChannelBindingProperties): AddressCipherPort {
        return AesGcmAddressCipher(base64Key = channelBindingProperties.encryptionKey)
    }

    @Bean
    fun channelBindingCommandService(
        channelBindingPersistencePort: ChannelBindingPersistencePort,
        activeUserValidator: ActiveUserValidator,
        clock: Clock,
        channelBindingProperties: ChannelBindingProperties,
        telegramProperties: TelegramProperties
    ): ChannelBindingCommandService {
        return ChannelBindingCommandService(
            channelBindingPersistencePort = channelBindingPersistencePort,
            activeUserValidator = activeUserValidator,
            clock = clock,
            linkTokenTtl = channelBindingProperties.linkTokenTtl,
            telegramBotUsername = telegramProperties.botUsername
        )
    }
}
