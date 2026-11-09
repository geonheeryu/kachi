package me.rgunny.kachi.ai.domain.llm

/**
 * LLM 호출 실패를 책임·지속 두 축과 함께 보존하는 값.
 *
 * 두 축은 [code]에서 파생한다. 분류 축이 코드와 어긋난 실패를 만들 수 없게 하려는 것이다.
 * 소비처가 내리는 판단마다 프로퍼티 하나가 있다. 서킷에 기록할 것인가([recordsInCircuit]),
 * 모델을 보류할 것인가([holdsModel]), 제공자를 보류할 것인가([holdsProvider]).
 * 격리는 실패 하나가 아니라 시도한 후보 전부의 책임 소재로 정하므로 여기 없다.
 *
 * 자세한 결정 배경은 docs/decisions/030-ai-service-llm-호출-단위와-실패-분류와-실호출-검증.md 를 참고한다.
 */
data class LlmFailure                           (
    val code: LlmFailureCode,
    /** 실패를 낸 제공자. 호출이 나가기 전에 차단된 실패는 어느 제공자의 것도 아니므로 null이다. */
    val provider: LlmProvider?,
    val message: String = code.defaultMessage,
    val statusCode: Int? = null,
    val retryAfterMillis: Long? = null
) {
    init {
        require(message.isNotBlank()) { "LLM 실패 메시지는 빈 값일 수 없습니다" }
        require(retryAfterMillis == null || retryAfterMillis >= 0) { "Retry-After는 음수일 수 없습니다" }
    }

    /** 로그와 메시지에 쓰는 제공자 code. 호출이 나가지 않은 실패는 [NO_PROVIDER]다. */
    val providerCode: String
        get() = provider?.code ?: NO_PROVIDER

    val attribution: LlmFailureAttribution
        get() = code.attribution

    /** 다음 tick이나 다음 후보에서 저절로 풀리는가. */
    val transient: Boolean
        get() = code.transient

    /**
     * 실제로 provider를 호출해서 얻은 실패인가.
     *
     * 호출 전에 차단해서 만든 실패는 provider의 상태에 대해 아무것도 말해주지 않는다.
     */
    val fromActualCall: Boolean
        get() = attribution != LlmFailureAttribution.NONE

    /**
     * 서킷 브레이커에 실패로 기록할 것인가.
     *
     * 실제 호출에서 나온 일시 실패만 표본이 된다. 응답 계약 위반은 모델이 살아 있다는 증거이고,
     * 404나 402는 한 건으로 확정이라 비율로 다룰 것이 아니다.
     */
    val recordsInCircuit: Boolean
        get() = fromActualCall && transient

    /** 이 모델을 한동안 후보에서 뺄 것인가. */
    val holdsModel: Boolean
        get() = attribution == LlmFailureAttribution.MODEL && !transient

    /** 이 제공자의 모든 모델을 한동안 후보에서 뺄 것인가. */
    val holdsProvider: Boolean
        get() = attribution == LlmFailureAttribution.PROVIDER && !transient

    companion object {
        const val NO_PROVIDER = "none"
    }
}
