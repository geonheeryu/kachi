package me.rgunny.kachi.ai.config

import me.rgunny.kachi.ai.application.port.inbound.keyword.model.ExpandKeywordsCommand
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 주기 키워드 확장 실행 설정.
 *
 * 확장 결과는 자주 바뀌지 않고 키워드마다 LLM을 호출하므로 기본 주기를 길게 둔다.
 */
@ConfigurationProperties(prefix = AiKeywordExpansionSchedulerProperties.PREFIX)
data class AiKeywordExpansionSchedulerProperties(
    val enabled: Boolean,
    val fixedDelay: Duration,
    val initialDelay: Duration,
    val maxExpansionsPerKeyword: Int,
) {
    companion object {
        const val PREFIX = "kachi.ai.scheduler.keyword-expansion"
    }

    init {
        require(maxExpansionsPerKeyword in 1..ExpandKeywordsCommand.MAX_EXPANSIONS_PER_KEYWORD) {
            "키워드별 최대 확장 개수는 1 이상 ${ExpandKeywordsCommand.MAX_EXPANSIONS_PER_KEYWORD} 이하여야 합니다"
        }
    }
}
