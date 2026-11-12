package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.adapter.inbound.scheduler.AiKeywordExpansionSchedulerSettings
import me.rgunny.kachi.ai.adapter.inbound.scheduler.AiStorySummarySchedulerSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * scheduler 설정을 각 scheduler가 보는 Settings로 조립하는 설정.
 */
@Configuration
class AiSchedulerConfig {

    @Bean
    fun aiKeywordExpansionSchedulerSettings(
        properties: AiKeywordExpansionSchedulerProperties
    ): AiKeywordExpansionSchedulerSettings {
        return AiKeywordExpansionSchedulerSettings(
            enabled = properties.enabled,
            fixedDelay = properties.fixedDelay,
            initialDelay = properties.initialDelay,
            maxExpansionsPerKeyword = properties.maxExpansionsPerKeyword
        )
    }

    @Bean
    fun aiStorySummarySchedulerSettings(
        properties: AiStorySummarySchedulerProperties
    ): AiStorySummarySchedulerSettings {
        return AiStorySummarySchedulerSettings(
            enabled = properties.enabled,
            fixedDelay = properties.fixedDelay,
            initialDelay = properties.initialDelay,
            maxStoriesPerTick = properties.maxStoriesPerTick
        )
    }
}
