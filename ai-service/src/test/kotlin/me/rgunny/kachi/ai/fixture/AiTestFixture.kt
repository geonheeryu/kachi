package me.rgunny.kachi.ai.fixture

import me.rgunny.kachi.ai.adapter.outbound.lock.InMemoryExecutionLockAdapter
import me.rgunny.kachi.ai.application.port.outbound.lock.ExecutionLockPort
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmGenerationMetadata
import me.rgunny.kachi.ai.application.port.outbound.news.model.NewsArticle
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.KeywordQuarantinedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.SummaryCreatedEvent
import me.rgunny.kachi.ai.application.service.outbox.AiOutboxRelayPolicy
import me.rgunny.kachi.ai.config.AiEventsProperties
import me.rgunny.kachi.ai.config.AiOutboxRelayProperties
import me.rgunny.kachi.ai.config.AiOutboxRetryProperties
import me.rgunny.kachi.ai.config.LlmCircuitBreakerProperties
import me.rgunny.kachi.ai.adapter.outbound.llm.LlmCooldownSettings
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmModelStatus
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmHold
import me.rgunny.kachi.ai.application.port.outbound.llm.model.LlmProbeResult
import me.rgunny.kachi.ai.adapter.outbound.llm.LlmHoldSettings
import me.rgunny.kachi.ai.config.LlmProperties
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.keyword.ExpandedKeyword
import me.rgunny.kachi.ai.domain.keyword.KeywordExpansion
import me.rgunny.kachi.ai.domain.outbox.AiOutboxClaim
import me.rgunny.kachi.ai.domain.outbox.AiOutboxId
import me.rgunny.kachi.ai.domain.outbox.AiOutboxStatus
import me.rgunny.kachi.ai.domain.quarantine.KeywordQuarantine
import me.rgunny.kachi.ai.domain.llm.LlmFailure
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmBilling
import me.rgunny.kachi.ai.domain.llm.LlmModel
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.LlmUse
import me.rgunny.kachi.ai.domain.llm.KeywordExpansionPrompt
import me.rgunny.kachi.ai.domain.llm.NewsSummaryPrompt
import me.rgunny.kachi.ai.domain.llm.PromptVersion
import me.rgunny.kachi.ai.domain.llm.StorySummaryPrompt
import me.rgunny.kachi.ai.domain.llm.TokenUsage
import me.rgunny.kachi.ai.domain.outbox.AiOutbox
import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.domain.outbox.AiOutboxRetryPolicy
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.run.AiRun
import me.rgunny.kachi.ai.domain.run.AiRunTargetType
import me.rgunny.kachi.ai.domain.story.AiStory
import me.rgunny.kachi.ai.domain.story.AiStoryArticle
import me.rgunny.kachi.ai.domain.story.StoryId
import me.rgunny.kachi.ai.domain.summary.NewsSummary
import me.rgunny.kachi.ai.domain.summary.NewsSummarySentiment
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.domain.summary.StorySummary
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantine
import me.rgunny.kachi.ai.domain.watermark.SummaryWatermark
import me.rgunny.kachi.ai.application.port.inbound.story.model.CreatedStorySummaryResult
import me.rgunny.kachi.ai.application.service.story.StoryQuarantinePolicy
import me.rgunny.kachi.ai.application.service.story.StorySummaryPolicy
import me.rgunny.kachi.ai.config.StorySummaryProperties
import me.rgunny.kachi.ai.domain.summary.StorySummaryId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ai-service 테스트가 공유하는 고정값과 도메인 픽스처.
 */
object AiTestFixture {
    val NOW: Instant = Instant.parse("2026-06-03T00:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    /**
     * 이 인스턴스 안에서만 유효한 실행 lock을 만든다.
     */
    fun executionLock(): ExecutionLockPort = InMemoryExecutionLockAdapter(CLOCK)

    /** 후보로 쓰는 모델 상수. */
    val LLM_MODEL: LlmModel = LlmModel.GROQ_QWEN3_27B
    val DEFAULT_HOLD_REPROBE_AFTER: Duration = Duration.ofHours(1)
    val PROVIDER: LlmProvider = LLM_MODEL.provider

    /** 응답이 보고한 모델 이름([REQUESTED_MODEL]과 달라도 되는 값). */
    const val MODEL: String = "test-model"

    /** 요청에 실은 모델 code. */
    val REQUESTED_MODEL: String = LLM_MODEL.code
    val NEWS_SUMMARY_PROMPT_VERSION: PromptVersion = NewsSummaryPrompt.version
    val STORY_SUMMARY_PROMPT_VERSION: PromptVersion = StorySummaryPrompt.version
    val KEYWORD_EXPANSION_PROMPT_VERSION: PromptVersion = KeywordExpansionPrompt.version
    val TOKEN_USAGE: TokenUsage = TokenUsage(inputTokens = 10, outputTokens = 20)

    val NEWS_ID: UUID = UUID.fromString("018f0000-0000-7000-8000-000000000001")
    val STORY_ID: StoryId = StoryId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"))
    val OTHER_STORY_ID: StoryId = StoryId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000bb"))

    const val OUTBOX_EVENT_KEY: String = "018f0000-0000-7000-8000-000000000009"
    const val OUTBOX_PAYLOAD: String = """{"schemaVersion":1,"keyword":"NVIDIA"}"""
    const val RELAY_PUBLISHER_ID: String = "relay-test"

    fun keyword(value: String = "NVIDIA"): AiKeyword {
        return AiKeyword.of(value)
    }

    fun newsArticle(
        id: UUID = NEWS_ID,
        title: String = "NVIDIA news"
    ): NewsArticle {
        return NewsArticle(
            id = id,
            source = "GOOGLE",
            title = title,
            url = "https://news.example.com/nvidia",
            publishedAt = NOW.minus(Duration.ofDays(1)),
            collectedAt = NOW.minus(Duration.ofDays(1)).plusSeconds(60),
            matchedKeywords = listOf("NVIDIA")
        )
    }

    fun outbox(
        eventType: AiOutboxEventType = AiOutboxEventType.SUMMARY_CREATED,
        eventKey: String = OUTBOX_EVENT_KEY,
        partitionKey: String = "NVIDIA",
        payload: String = OUTBOX_PAYLOAD,
        now: Instant = NOW
    ): AiOutbox {
        return AiOutbox.create(
            eventType = eventType,
            eventKey = eventKey,
            partitionKey = partitionKey,
            payload = payload,
            now = now
        )
    }

    /**
     * 저장소에 있던 것처럼 상태·재시도 횟수·소유권을 지정해 복원한 outbox.
     *
     * 기본값은 [outbox]와 같은 PENDING 행이다.
     * 발행 중이거나 실패가 쌓인 행([outbox]로는 만들 수 없는 상태)도 만든다.
     */
    fun restoredOutbox(
        id: AiOutboxId = AiOutboxId.newId(),
        eventType: AiOutboxEventType = AiOutboxEventType.SUMMARY_CREATED,
        eventKey: String = OUTBOX_EVENT_KEY,
        partitionKey: String = "NVIDIA",
        payload: String = OUTBOX_PAYLOAD,
        status: AiOutboxStatus = AiOutboxStatus.PENDING,
        retryCount: Int = 0,
        nextRetryAt: Instant = NOW,
        lastError: String? = null,
        publishedAt: Instant? = null,
        claim: AiOutboxClaim? = null,
        createdAt: Instant = NOW,
        updatedAt: Instant = createdAt
    ): AiOutbox {
        return AiOutbox.restore(
            id = id,
            eventType = eventType,
            eventKey = eventKey,
            partitionKey = partitionKey,
            payload = payload,
            status = status,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
            lastError = lastError,
            publishedAt = publishedAt,
            claim = claim,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /**
     * 재시도 정책을 만든다.
     *
     * [jitterRatio] 기본값은 0이다(분산이 있으면 다음 차례 시각을 단언할 수 없음).
     */
    fun retryPolicy(
        maxAttempts: Int = 5,
        baseDelay: Duration = Duration.ofSeconds(1),
        maxDelay: Duration = Duration.ofMinutes(1),
        multiplier: Double = 2.0,
        jitterRatio: Double = 0.0
    ): AiOutboxRetryPolicy {
        return AiOutboxRetryPolicy(
            maxAttempts = maxAttempts,
            baseDelay = baseDelay,
            maxDelay = maxDelay,
            multiplier = multiplier,
            jitterRatio = jitterRatio
        )
    }

    fun relayPolicy(
        batchSize: Int = 10,
        publisherId: String = RELAY_PUBLISHER_ID,
        retryPolicy: AiOutboxRetryPolicy = retryPolicy(),
        publishingVisibilityTimeout: Duration = Duration.ofSeconds(60)
    ): AiOutboxRelayPolicy {
        return AiOutboxRelayPolicy(
            batchSize = batchSize,
            publisherId = publisherId,
            retryPolicy = retryPolicy,
            publishingVisibilityTimeout = publishingVisibilityTimeout
        )
    }

    fun relayProperties(
        enabled: Boolean = true,
        publisherId: String = RELAY_PUBLISHER_ID,
        fixedDelay: Duration = Duration.ofSeconds(5),
        initialDelay: Duration = Duration.ofSeconds(15),
        batchSize: Int = 50,
        publishingVisibilityTimeout: Duration = Duration.ofSeconds(60),
        retry: AiOutboxRetryProperties = retryProperties()
    ): AiOutboxRelayProperties {
        return AiOutboxRelayProperties(
            enabled = enabled,
            publisherId = publisherId,
            fixedDelay = fixedDelay,
            initialDelay = initialDelay,
            batchSize = batchSize,
            publishingVisibilityTimeout = publishingVisibilityTimeout,
            retry = retry
        )
    }

    const val EVENT_TOPIC_SUMMARY_CREATED: String = "ai.summary.created"
    const val EVENT_TOPIC_KEYWORD_QUARANTINED: String = "ai.keyword.quarantined"
    const val EVENT_TOPIC_STORY_SPLIT_REQUESTED: String = "ai.story.split-requested"
    const val EVENT_TOPIC_STORY_QUARANTINED: String = "ai.story.quarantined"
    const val EVENT_TOPIC_STORY_ARTICLE_ATTACHED: String = "story.article.attached"
    const val EVENT_TOPIC_STORY_MERGED: String = "story.merged"

    fun eventsProperties(
        enabled: Boolean = true,
        summaryCreatedTopic: String = EVENT_TOPIC_SUMMARY_CREATED,
        keywordQuarantinedTopic: String = EVENT_TOPIC_KEYWORD_QUARANTINED,
        storySplitRequestedTopic: String = EVENT_TOPIC_STORY_SPLIT_REQUESTED,
        storyQuarantinedTopic: String = EVENT_TOPIC_STORY_QUARANTINED
    ): AiEventsProperties {
        return AiEventsProperties(
            enabled = enabled,
            topics = AiEventsProperties.Topics(
                summaryCreated = summaryCreatedTopic,
                keywordQuarantined = keywordQuarantinedTopic,
                storySplitRequested = storySplitRequestedTopic,
                storyQuarantined = storyQuarantinedTopic
            )
        )
    }

    fun retryProperties(
        maxAttempts: Int = 5,
        baseDelay: Duration = Duration.ofSeconds(1),
        maxDelay: Duration = Duration.ofMinutes(1),
        multiplier: Double = 2.0
    ): AiOutboxRetryProperties {
        return AiOutboxRetryProperties(
            maxAttempts = maxAttempts,
            baseDelay = baseDelay,
            maxDelay = maxDelay,
            multiplier = multiplier
        )
    }

    fun summaryCreatedEvent(summary: NewsSummary = newsSummary()): SummaryCreatedEvent {
        return SummaryCreatedEvent.from(summary)
    }

    /**
     * 격리 상태 기록에서 만든 격리 이벤트.
     *
     * 기본값은 임계치에 막 도달한 기록이다.
     */
    fun keywordQuarantinedEvent(
        quarantine: KeywordQuarantine = quarantine(consecutiveFailures = DEFAULT_QUARANTINE_FAILURE_THRESHOLD)
    ): KeywordQuarantinedEvent {
        return KeywordQuarantinedEvent.from(quarantine)
    }

    fun keywordExpansion(
        keyword: AiKeyword = keyword(),
        expandedKeywords: List<String> = listOf("AI 반도체", "GPU"),
        provider: LlmProvider = PROVIDER,
        model: String = MODEL,
        requestedModel: String = REQUESTED_MODEL,
        createdAt: Instant = NOW
    ): KeywordExpansion {
        return KeywordExpansion.create(
            keyword = keyword,
            expandedKeywords = expandedKeywords.map(ExpandedKeyword::of),
            provider = provider,
            model = model,
            requestedModel = requestedModel,
            promptVersion = KEYWORD_EXPANSION_PROMPT_VERSION,
            createdAt = createdAt
        )
    }

    fun newsSummary(
        keyword: AiKeyword = keyword(),
        sourceNewsIds: List<UUID> = listOf(NEWS_ID),
        newsHash: String = "news-hash",
        provider: LlmProvider = PROVIDER,
        model: String = MODEL,
        requestedModel: String = REQUESTED_MODEL,
        createdAt: Instant = NOW
    ): NewsSummary {
        return NewsSummary.create(
            keyword = keyword,
            sourceNewsIds = sourceNewsIds,
            newsHash = newsHash,
            title = "${keyword.value} 기존 요약",
            content = "기존 요약 본문",
            sentiment = NewsSummarySentiment.NEUTRAL,
            provider = provider,
            model = model,
            requestedModel = requestedModel,
            promptVersion = NEWS_SUMMARY_PROMPT_VERSION,
            tokenUsage = TOKEN_USAGE,
            createdAt = createdAt
        )
    }

    fun newsSummaryMetadata(): LlmGenerationMetadata {
        return LlmGenerationMetadata(
            provider = PROVIDER,
            requestedModel = REQUESTED_MODEL,
            model = MODEL,
            promptVersion = NEWS_SUMMARY_PROMPT_VERSION,
            tokenUsage = TOKEN_USAGE
        )
    }

    fun storySummaryMetadata(): LlmGenerationMetadata {
        return LlmGenerationMetadata(
            provider = PROVIDER,
            requestedModel = REQUESTED_MODEL,
            model = MODEL,
            promptVersion = STORY_SUMMARY_PROMPT_VERSION,
            tokenUsage = TOKEN_USAGE
        )
    }

    fun keywordExpansionMetadata(): LlmGenerationMetadata {
        return LlmGenerationMetadata(
            provider = PROVIDER,
            requestedModel = REQUESTED_MODEL,
            model = MODEL,
            promptVersion = KEYWORD_EXPANSION_PROMPT_VERSION,
            tokenUsage = TOKEN_USAGE
        )
    }

    fun completedRun(
        targetType: AiRunTargetType,
        requestedKeywords: Int,
        succeededCount: Int = requestedKeywords,
        failureCount: Int = 0,
        failureReason: AiFailureReason? = null,
        startedAt: Instant = NOW,
        finishedAt: Instant = NOW.plusSeconds(5),
        windowFrom: Instant? = null,
        windowTo: Instant? = null,
        watermarkAdvanced: Boolean = false
    ): AiRun {
        return AiRun.start(
            targetType = targetType,
            requestedKeywords = requestedKeywords,
            startedAt = startedAt,
            windowFrom = windowFrom,
            windowTo = windowTo
        ).complete(
            succeededCount = succeededCount,
            failureCount = failureCount,
            failureReason = failureReason,
            provider = null,
            model = null,
            promptVersion = null,
            finishedAt = finishedAt,
            watermarkAdvanced = watermarkAdvanced
        )
    }

    fun watermark(
        position: Instant,
        targetType: AiRunTargetType = AiRunTargetType.NEWS_SUMMARY,
        updatedAt: Instant = position
    ): SummaryWatermark {
        return SummaryWatermark.initial(
            targetType = targetType,
            position = position,
            updatedAt = updatedAt
        )
    }

    /**
     * 연속 실패가 [consecutiveFailures]회 누적된 격리 기록을 만든다.
     *
     * [failureThreshold]에 도달하면 격리 상태가 된다.
     */
    fun quarantine(
        keyword: AiKeyword = keyword(),
        consecutiveFailures: Int,
        failureThreshold: Int = DEFAULT_QUARANTINE_FAILURE_THRESHOLD,
        targetType: AiRunTargetType = AiRunTargetType.NEWS_SUMMARY,
        updatedAt: Instant = NOW
    ): KeywordQuarantine {
        return (1..consecutiveFailures).fold(
            KeywordQuarantine.track(targetType = targetType, keyword = keyword, updatedAt = updatedAt)
        ) { quarantine, _ ->
            quarantine.recordFailure(
                reason = AiFailureReason.UNKNOWN,
                failureThreshold = failureThreshold,
                updatedAt = updatedAt
            )
        }
    }

    /**
     * 분류된 LLM 실패를 만든다.
     */
    fun llmFailure(
        code: LlmFailureCode,
        statusCode: Int? = null,
        retryAfterMillis: Long? = null
    ): LlmFailure {
        return LlmFailure(
            code = code,
            provider = PROVIDER,
            message = "test ${code.name} failure",
            statusCode = statusCode,
            retryAfterMillis = retryAfterMillis
        )
    }

    fun llmProviderException(code: LlmFailureCode): LlmProviderException {
        return LlmProviderException(llmFailure(code))
    }

    /**
     * 후보 여럿을 거친 뒤의 실패를 만든다.
     *
     * 대표 실패는 마지막 시도이고 [codes]가 호출 순서다.
     */
    fun llmProviderException(vararg codes: LlmFailureCode): LlmProviderException {
        require(codes.isNotEmpty())
        val attempts = codes.map { llmFailure(it) }

        return LlmProviderException(failure = attempts.last(), attempts = attempts)
    }

    fun holdSettings(reprobeAfter: Duration = DEFAULT_HOLD_REPROBE_AFTER): LlmHoldSettings {
        return LlmHoldSettings(reprobeAfter = reprobeAfter)
    }

    fun llmModelStatus(
        model: LlmModel = LLM_MODEL,
        billing: LlmBilling = LlmBilling.FREE_TIER,
        circuitBreakerState: String = "CLOSED",
        cooldownUntil: Instant? = null,
        hold: LlmHold? = null
    ): LlmModelStatus {
        return LlmModelStatus(
            model = model,
            billing = billing,
            circuitBreakerState = circuitBreakerState,
            cooldownUntil = cooldownUntil,
            hold = hold,
            failureRate = 50f,
            slowCallRate = -1f,
            bufferedCalls = 4,
            successfulCalls = 2,
            failedCalls = 2,
            notPermittedCalls = 3
        )
    }

    fun llmProbeResult(
        model: LlmModel = LLM_MODEL,
        billing: LlmBilling = LlmBilling.FREE_TIER,
        latency: Duration = Duration.ofMillis(1234)
    ): LlmProbeResult {
        return LlmProbeResult(
            model = model,
            billing = billing,
            metadata = keywordExpansionMetadata(),
            latency = latency,
            expandedKeywords = listOf(ExpandedKeyword.of("AI 반도체"), ExpandedKeyword.of("GPU"))
        )
    }

    /**
     * 429 응답 실패를 만든다.
     *
     * [retryAfterMillis]가 null이면 Retry-After 헤더가 없는 응답이다.
     */
    fun rateLimitedException(retryAfterMillis: Long?): LlmProviderException {
        return LlmProviderException(
            llmFailure(
                code = LlmFailureCode.LLM_RATE_LIMITED,
                statusCode = 429,
                retryAfterMillis = retryAfterMillis
            )
        )
    }

    fun storyQuarantinePolicy(
        failureThreshold: Int = DEFAULT_QUARANTINE_FAILURE_THRESHOLD
    ): StoryQuarantinePolicy {
        return StoryQuarantinePolicy(failureThreshold = failureThreshold)
    }

    fun circuitBreakerProperties(
        slidingWindowSize: Int = 6,
        minimumNumberOfCalls: Int = 3,
        slowCallRateThreshold: Float = 80f
    ): LlmCircuitBreakerProperties {
        return LlmCircuitBreakerProperties(
            slidingWindowSize = slidingWindowSize,
            minimumNumberOfCalls = minimumNumberOfCalls,
            failureRateThreshold = 50f,
            slowCallRateThreshold = slowCallRateThreshold,
            waitDurationInOpenState = Duration.ofSeconds(60),
            permittedNumberOfCallsInHalfOpenState = 2
        )
    }

    fun providerProperties(
        billing: LlmBilling = LlmBilling.FREE_TIER,
        apiKey: String = "test-key",
        baseUrl: String = "https://llm.example.com/v1",
        connectTimeout: Duration = Duration.ofSeconds(2)
    ): LlmProperties.ProviderProperties {
        return LlmProperties.ProviderProperties(
            baseUrl = baseUrl,
            apiKey = apiKey,
            billing = billing,
            connectTimeout = connectTimeout
        )
    }

    fun modelProperties(
        timeout: Duration = Duration.ofSeconds(10),
        slowAfter: Duration = Duration.ofSeconds(8)
    ): LlmProperties.ModelProperties {
        return LlmProperties.ModelProperties(timeout = timeout, slowAfter = slowAfter)
    }

    /**
     * 클라우드 둘을 후보로 두고 Ollama는 정의만 있는 설정을 만든다.
     */
    fun llmProperties(
        providers: Map<LlmProvider, LlmProperties.ProviderProperties> = mapOf(
            LlmProvider.GROQ to providerProperties(),
            LlmProvider.MISTRAL to providerProperties(),
            LlmProvider.OLLAMA to providerProperties(billing = LlmBilling.SELF_HOSTED, apiKey = "")
        ),
        models: Map<LlmModel, LlmProperties.ModelProperties> = mapOf(
            LlmModel.GROQ_QWEN3_27B to modelProperties(),
            LlmModel.MISTRAL_SMALL_2603 to modelProperties(timeout = Duration.ofSeconds(20), slowAfter = Duration.ofSeconds(15)),
            LlmModel.OLLAMA_QWEN3_27B to modelProperties(timeout = Duration.ofSeconds(150), slowAfter = Duration.ofSeconds(120))
        ),
        uses: Map<LlmUse, LlmProperties.UseProperties> = mapOf(
            LlmUse.NEWS_SUMMARY to LlmProperties.UseProperties(listOf(LlmModel.GROQ_QWEN3_27B, LlmModel.MISTRAL_SMALL_2603)),
            LlmUse.STORY_SUMMARY to LlmProperties.UseProperties(listOf(LlmModel.GROQ_QWEN3_27B, LlmModel.MISTRAL_SMALL_2603)),
            LlmUse.KEYWORD_EXPANSION to LlmProperties.UseProperties(listOf(LlmModel.MISTRAL_SMALL_2603))
        )
    ): LlmProperties {
        return LlmProperties(
            providers = providers,
            models = models,
            uses = uses,
            guard = LlmProperties.GuardProperties(
                circuitBreaker = circuitBreakerProperties(),
                cooldown = LlmCooldownSettings(default = Duration.ofSeconds(60), max = Duration.ofMinutes(10)),
                hold = holdSettings()
            )
        )
    }

    const val DEFAULT_QUARANTINE_FAILURE_THRESHOLD = 3

    fun storySummaryPolicy(
        minNewArticles: Int = 3,
        maxWait: Duration = Duration.ofMinutes(60),
        maxArticlesPerVersion: Int = 50
    ): StorySummaryPolicy {
        return StorySummaryPolicy(
            minNewArticles = minNewArticles,
            maxWait = maxWait,
            maxArticlesPerVersion = maxArticlesPerVersion
        )
    }

    fun storySummaryProperties(
        minNewArticles: Int = 3,
        maxWait: Duration = Duration.ofMinutes(60),
        maxArticlesPerVersion: Int = 50
    ): StorySummaryProperties {
        return StorySummaryProperties(
            minNewArticles = minNewArticles,
            maxWait = maxWait,
            maxArticlesPerVersion = maxArticlesPerVersion
        )
    }

    fun aiStory(
        storyId: StoryId = STORY_ID,
        keywords: List<String> = listOf("NVIDIA"),
        articleCount: Int = 1,
        attachedAt: Instant = NOW,
        now: Instant = NOW
    ): AiStory {
        return AiStory.open(
            storyId = storyId,
            keywords = keywords.map(AiKeyword::of),
            articleCount = articleCount,
            attachedAt = attachedAt,
            now = now
        )
    }

    fun storyArticle(
        newsId: UUID = NEWS_ID,
        storyId: StoryId = STORY_ID,
        title: String = "NVIDIA news",
        attachedAt: Instant = NOW
    ): AiStoryArticle {
        return AiStoryArticle.create(
            newsId = newsId,
            storyId = storyId,
            source = "GOOGLE",
            title = title,
            excerpt = "$title excerpt",
            url = "https://news.example.com/nvidia",
            publishedAt = attachedAt.minusSeconds(600),
            attachedAt = attachedAt
        )
    }

    fun storySummary(
        storyId: StoryId = STORY_ID,
        version: Long = 1,
        keywords: List<String> = listOf("NVIDIA"),
        newNewsIds: List<UUID> = listOf(NEWS_ID),
        sourceNewsCount: Int = newNewsIds.size,
        developmentKind: StoryDevelopmentKind = StoryDevelopmentKind.DEVELOPMENT,
        createdAt: Instant = NOW
    ): StorySummary {
        return StorySummary.create(
            storyId = storyId,
            version = version,
            keywords = keywords.map(AiKeyword::of),
            newNewsIds = newNewsIds,
            sourceNewsCount = sourceNewsCount,
            title = "story 요약 v$version",
            content = "story 요약 본문",
            sentiment = NewsSummarySentiment.NEUTRAL,
            developmentKind = developmentKind,
            provider = PROVIDER,
            model = MODEL,
            requestedModel = REQUESTED_MODEL,
            promptVersion = STORY_SUMMARY_PROMPT_VERSION,
            tokenUsage = TOKEN_USAGE,
            createdAt = createdAt
        )
    }

    fun createdResult(
        storyId: StoryId = STORY_ID,
        version: Long = 1,
        developmentKind: StoryDevelopmentKind = StoryDevelopmentKind.DEVELOPMENT,
        published: Boolean = true
    ): CreatedStorySummaryResult {
        return CreatedStorySummaryResult(
            summaryId = StorySummaryId.newId(),
            storyId = storyId,
            version = version,
            developmentKind = developmentKind,
            newArticleCount = 1,
            published = published
        )
    }

    /**
     * 연속 실패가 [consecutiveFailures]회 누적된 story 격리 기록을 만든다.
     *
     * [failureThreshold]에 도달하면 격리 상태가 된다.
     */
    fun storyQuarantine(
        storyId: StoryId = STORY_ID,
        consecutiveFailures: Int,
        failureThreshold: Int = DEFAULT_QUARANTINE_FAILURE_THRESHOLD,
        updatedAt: Instant = NOW
    ): StoryQuarantine {
        return (1..consecutiveFailures).fold(
            StoryQuarantine.track(storyId = storyId, updatedAt = updatedAt)
        ) { quarantine, _ ->
            quarantine.recordFailure(
                reason = AiFailureReason.UNKNOWN,
                failureThreshold = failureThreshold,
                updatedAt = updatedAt
            )
        }
    }
}
