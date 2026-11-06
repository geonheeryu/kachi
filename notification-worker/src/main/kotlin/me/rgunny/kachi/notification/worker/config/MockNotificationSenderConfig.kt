package me.rgunny.kachi.notification.worker.config

import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.worker.adapter.outbound.sender.MockNotificationSender
import me.rgunny.kachi.notification.worker.adapter.outbound.sender.MockNotificationSenderMode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.slf4j.LoggerFactory

/**
 * mock sender adapter 설정.
 *
 * sender adapter가 없으면 worker는 dispatch 메시지를 consume해도 실제 발송 단계에서 sender not found로 끝난다.
 * mock sender를 켜면 vendor 없이 상태 전이와 retry classification을 확인할 수 있다.
 * 실제 sender가 켜진 채널은 mock 지원 채널에서 자동 제외해 sender 중복을 막는다.
 */
@Configuration
class MockNotificationSenderConfig {

    @Bean
    @ConditionalOnProperty(
        prefix = "kachi.notification.sender.mock",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun mockNotificationSender(properties: NotificationSenderProperties): MockNotificationSender {
        val mock = properties.mock
        val configuredChannels = mock.channels.map { channel ->
            NotificationChannel.valueOf(channel.uppercase())
        }.toSet()
        val mode = MockNotificationSenderMode.valueOf(mock.mode.uppercase())
        val realSenderChannels = realSenderChannels(properties)
        val disabledMockChannels = configuredChannels.intersect(realSenderChannels)
        val effectiveChannels = configuredChannels - realSenderChannels

        require(configuredChannels.isNotEmpty()) { "mock sender channels must not be empty" }

        if (disabledMockChannels.isNotEmpty()) {
            log.warn(
                "Real notification sender is enabled while mock sender also includes channels={}; real sender will handle those channels and mock sender support has been disabled. effectiveMockChannels={}",
                disabledMockChannels,
                effectiveChannels,
            )
        }

        return MockNotificationSender(
            channels = effectiveChannels,
            mode = mode,
        )
    }

    private fun realSenderChannels(properties: NotificationSenderProperties): Set<NotificationChannel> {
        return realSenderBindings(properties)
            .filter { it.enabled }
            .map { it.channel }
            .toSet()
    }

    private fun realSenderBindings(properties: NotificationSenderProperties): List<RealSenderBinding> {
        return listOf(
            RealSenderBinding(
                channel = NotificationChannel.SLACK,
                enabled = properties.slack.enabled,
            ),
            RealSenderBinding(
                channel = NotificationChannel.DISCORD,
                enabled = properties.discord.enabled,
            ),
            RealSenderBinding(
                channel = NotificationChannel.TELEGRAM,
                enabled = properties.telegram.enabled,
            )
        )
    }

    private data class RealSenderBinding(
        val channel: NotificationChannel,
        val enabled: Boolean,
    )

    private companion object {
        val log = LoggerFactory.getLogger(MockNotificationSenderConfig::class.java)
    }
}
