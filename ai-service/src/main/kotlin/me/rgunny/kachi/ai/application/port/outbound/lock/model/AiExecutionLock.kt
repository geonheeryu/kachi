package me.rgunny.kachi.ai.application.port.outbound.lock.model

/**
 * ai-service가 lock으로 보호하는 실행 단위.
 */
enum class AiExecutionLock(
    override val key: String,
    override val scope: ExecutionLockScope
) : ExecutionLockTarget {

    /**
     * 클러스터에서 한 번만 도는 뉴스 요약 실행(LLM 호출 비용).
     */
    NEWS_SUMMARY("ai-service:news-summary", ExecutionLockScope.CLUSTER),

    /**
     * 클러스터에서 한 번만 도는 키워드 확장 실행(LLM 호출 비용).
     */
    KEYWORD_EXPANSION("ai-service:keyword-expansion", ExecutionLockScope.CLUSTER),

    /**
     * maxWait를 넘긴 story를 훑는 tick.
     */
    STORY_SUMMARY("ai-service:story-summary", ExecutionLockScope.CLUSTER),

    /**
     * 같은 인스턴스에서 앞선 tick이 끝나기 전 다음 tick의 재조회를 막는 outbox relay(중복 발행 차단은 저장소 조건부 쓰기).
     */
    OUTBOX_RELAY("ai-service:outbox-relay", ExecutionLockScope.INSTANCE)
}
