package me.rgunny.kachi.ai.application.service.news

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.exception.KeywordReaderErrorCode
import me.rgunny.kachi.ai.application.exception.KeywordReaderException
import me.rgunny.kachi.ai.application.exception.NewsReaderErrorCode
import me.rgunny.kachi.ai.application.exception.NewsReaderException
import me.rgunny.kachi.ai.application.port.inbound.news.model.ExplicitSummaryWindowRequest
import me.rgunny.kachi.ai.application.port.inbound.news.model.SummarizeNewsCommand
import me.rgunny.kachi.ai.application.port.inbound.news.model.WatermarkSummaryWindowRequest
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.KeywordQuarantinedEvent
import me.rgunny.kachi.ai.application.port.outbound.outbox.model.SummaryCreatedEvent
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.domain.outbox.AiOutboxStatus
import me.rgunny.kachi.ai.domain.quarantine.KeywordQuarantineStatus
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.run.AiRunStatus
import me.rgunny.kachi.ai.domain.run.AiRunTargetType
import me.rgunny.kachi.ai.domain.run.AiSkipReason
import me.rgunny.kachi.ai.domain.summary.NewsHash
import me.rgunny.kachi.ai.fake.FakeAiOutboxEventSerializer
import me.rgunny.kachi.ai.fake.FakeAiRunPersistencePort
import me.rgunny.kachi.ai.fake.FakeKeywordQuarantinePersistencePort
import me.rgunny.kachi.ai.fake.FakeKeywordReaderPort
import me.rgunny.kachi.ai.fake.FakeLlmProviderPort
import me.rgunny.kachi.ai.fake.FakeNewsReaderPort
import me.rgunny.kachi.ai.fake.FakeNewsSummaryPersistencePort
import me.rgunny.kachi.ai.fake.FakeSummaryWatermarkPersistencePort
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("SummarizeNewsService")
class SummarizeNewsServiceTest {
    private val now = AiTestFixture.NOW
    private val clock = AiTestFixture.CLOCK
    private val overlap = Duration.ofMinutes(5)
    private val maxLookback = Duration.ofHours(6)
    private val keywordReader = FakeKeywordReaderPort()
    private val newsReader = FakeNewsReaderPort()
    private val llmProvider = FakeLlmProviderPort()
    private val newsSummaryPersistence = FakeNewsSummaryPersistencePort()
    private val aiRunPersistence = FakeAiRunPersistencePort()
    private val watermarkPersistence = FakeSummaryWatermarkPersistencePort()
    private val quarantinePersistence = FakeKeywordQuarantinePersistencePort()
    private val eventSerializer = FakeAiOutboxEventSerializer()

    @Test
    @DisplayName("키워드별 뉴스를 요약하고 실행 기록을 성공 상태로 완료한다")
    fun summarizeNews() = runBlocking {
        newsReader.articlesByKeyword = mapOf(AiKeyword.of("NVIDIA") to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(
                keywords = listOf(AiKeyword.of("NVIDIA")),
                window = explicitWindow(),
                maxArticlesPerKeyword = 20
            )
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertEquals(1, result.requestedKeywords)
        assertEquals(1, result.succeededCount)
        assertEquals(0, result.failureCount)
        assertEquals(1, result.summaries.size)
        assertEquals("NVIDIA 요약", result.summaries.first().title)
        assertEquals(false, result.summaries.first().reused)
        assertEquals(1, newsSummaryPersistence.savedSummaries.size)
        assertEquals("NVIDIA 요약", newsSummaryPersistence.savedSummaries.first().title)
        assertEquals(AiTestFixture.MODEL, newsSummaryPersistence.savedSummaries.first().model)
        assertEquals(AiTestFixture.REQUESTED_MODEL, newsSummaryPersistence.savedSummaries.first().requestedModel)
        assertEquals(2, aiRunPersistence.savedRuns.size)
        assertEquals(AiTestFixture.PROVIDER, aiRunPersistence.savedRuns.last().provider)
        assertEquals(AiTestFixture.MODEL, aiRunPersistence.savedRuns.last().model)
        assertEquals(AiTestFixture.NEWS_SUMMARY_PROMPT_VERSION, aiRunPersistence.savedRuns.last().promptVersion)
    }

    @Test
    @DisplayName("같은 입력의 기존 요약이 있으면 LLM을 호출하지 않고 성공 처리한다")
    fun reuseExistingSummaryBeforeLlmCall() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(article))
        newsSummaryPersistence.existingSummaries += AiTestFixture.newsSummary(
            keyword = keyword,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = keyword, sourceNewsIds = listOf(article.id))
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = explicitWindow())
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertEquals(1, result.summaries.size)
        assertEquals("NVIDIA 기존 요약", result.summaries.first().title)
        assertEquals(true, result.summaries.first().reused)
        assertEquals(0, llmProvider.summarizeCallCount)
        assertEquals(0, newsSummaryPersistence.savedSummaries.size)
    }

    @Test
    @DisplayName("구간이 달라도 같은 뉴스 묶음이면 기존 요약을 재사용한다")
    fun reuseExistingSummaryWhenWindowDiffers() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(article))
        newsSummaryPersistence.existingSummaries += AiTestFixture.newsSummary(
            keyword = keyword,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = keyword, sourceNewsIds = listOf(article.id))
        )
        val service = service()

        // 실패 후 재시도는 watermark가 유지된 채 구간의 끝만 앞으로 밀린 상태로 다시 들어온다.
        val result = service.summarize(
            SummarizeNewsCommand(
                keywords = listOf(keyword),
                window = ExplicitSummaryWindowRequest(
                    from = now.minus(Duration.ofHours(1)),
                    to = now.plus(Duration.ofMinutes(10))
                )
            )
        )

        assertEquals(true, result.summaries.first().reused)
        assertEquals(0, llmProvider.summarizeCallCount)
    }

    @Test
    @DisplayName("다른 provider/model이 만든 기존 요약도 재사용한다")
    fun reuseExistingSummaryFromAnotherProvider() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(article))
        // 선조회 키에 model이 없으므로 failover로 다른 provider가 만든 요약도 같은 키로 걸린다.
        newsSummaryPersistence.existingSummaries += AiTestFixture.newsSummary(
            keyword = keyword,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = keyword, sourceNewsIds = listOf(article.id)),
            provider = LlmProvider.MISTRAL,
            model = "mistral-small-2603"
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = explicitWindow())
        )

        assertEquals(true, result.summaries.single().reused)
        assertEquals(0, llmProvider.summarizeCallCount)
        assertEquals(0, newsSummaryPersistence.savedSummaries.size)
        assertEquals(LlmProvider.MISTRAL, aiRunPersistence.savedRuns.last().provider)
        assertEquals("mistral-small-2603", aiRunPersistence.savedRuns.last().model)
    }

    @Test
    @DisplayName("기존 요약이 없으면 LLM 호출 후 저장한다")
    fun callLlmAndSaveWhenSummaryDoesNotExist() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = ExplicitSummaryWindowRequest(null, null))
        )

        assertEquals(1, result.summaries.size)
        assertEquals(false, result.summaries.first().reused)
        assertEquals(1, llmProvider.summarizeCallCount)
        assertEquals(1, newsSummaryPersistence.savedSummaries.size)
    }

    @Test
    @DisplayName("저장 중 중복이 발생하면 기존 요약을 반환해 idempotent하게 처리한다")
    fun handleDuplicateOnSaveIdempotently() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(article))
        newsSummaryPersistence.duplicateOnSave = true
        newsSummaryPersistence.existingAfterDuplicate = AiTestFixture.newsSummary(
            keyword = keyword,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = keyword, sourceNewsIds = listOf(article.id))
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = ExplicitSummaryWindowRequest(null, null))
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertEquals(1, result.summaries.size)
        assertEquals(true, result.summaries.first().reused)
        assertEquals(1, llmProvider.summarizeCallCount)
        assertEquals(1, newsSummaryPersistence.saveOrFindExistingCallCount)
    }

    @Test
    @DisplayName("command에 키워드가 없으면 활성 키워드를 읽어 요약한다")
    fun readActiveKeywordsWhenCommandKeywordsAreEmpty() = runBlocking {
        keywordReader.keywords = listOf(AiKeyword.of("NVIDIA"))
        newsReader.articlesByKeyword = mapOf(AiKeyword.of("NVIDIA") to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = emptyList(), window = ExplicitSummaryWindowRequest(null, null))
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertEquals(1, keywordReader.readCount)
        assertEquals(1, result.requestedKeywords)
    }

    @Test
    @DisplayName("요약 대상 뉴스가 없으면 실패가 아니라 skip으로 집계한다")
    fun skipKeywordWhenArticlesAreEmpty() = runBlocking {
        newsReader.articlesByKeyword = mapOf(AiKeyword.of("NVIDIA") to emptyList())
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(
                keywords = listOf(AiKeyword.of("NVIDIA")),
                window = ExplicitSummaryWindowRequest(null, null)
            )
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertEquals(0, result.succeededCount)
        assertEquals(0, result.failureCount)
        assertEquals(1, result.skippedCount)
        assertEquals(AiSkipReason.NO_INPUT, result.skipReason)
        assertNull(aiRunPersistence.savedRuns.last().failureReason)
        assertEquals(0, newsSummaryPersistence.savedSummaries.size)
        assertEquals(0, quarantinePersistence.saveCount)
    }

    @Test
    @DisplayName("뉴스가 없어 skip한 키워드는 watermark 전진을 막지 않는다")
    fun advanceWatermarkWhenKeywordIsSkipped() = runBlocking {
        val summarized = AiKeyword.of("NVIDIA")
        val empty = AiKeyword.of("TESLA")
        newsReader.articlesByKeyword = mapOf(
            summarized to listOf(AiTestFixture.newsArticle()),
            empty to emptyList()
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(summarized, empty), window = watermarkWindow())
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertEquals(1, result.succeededCount)
        assertEquals(1, result.skippedCount)
        assertTrue(result.watermarkAdvanced)
        assertEquals(now, watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY]?.position)
    }

    @Test
    @DisplayName("watermark가 있으면 그 지점에서 overlap만큼 물러난 구간을 요약한다")
    fun resumeFromWatermark() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val position = now.minus(Duration.ofMinutes(30))
        watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY] = AiTestFixture.watermark(position)
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertEquals(position.minus(overlap), result.windowFrom)
        assertEquals(now, result.windowTo)
    }

    @Test
    @DisplayName("모든 키워드가 성공하면 watermark를 구간의 끝으로 전진시킨다")
    fun advanceWatermarkWhenAllKeywordsSucceed() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertTrue(result.watermarkAdvanced)
        assertEquals(now, watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY]?.position)
        assertTrue(aiRunPersistence.savedRuns.last().watermarkAdvanced)
    }

    @Test
    @DisplayName("watermark가 구간의 끝보다 앞서 있으면 전진하지 않았다고 기록한다")
    fun recordNotAdvancedWhenWatermarkIsAhead() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val future = now.plus(Duration.ofMinutes(10))
        watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY] = AiTestFixture.watermark(future)
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertFalse(result.watermarkAdvanced)
        assertFalse(aiRunPersistence.savedRuns.last().watermarkAdvanced)
        assertEquals(0, watermarkPersistence.saveCount)
        assertEquals(future, watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY]?.position)
    }

    @Test
    @DisplayName("키워드 하나라도 실패하면 watermark를 유지해 다음 실행이 같은 구간을 다시 처리한다")
    fun keepWatermarkWhenAnyKeywordFails() = runBlocking {
        val succeeded = AiKeyword.of("NVIDIA")
        val failed = AiKeyword.of("TESLA")
        newsReader.articlesByKeyword = mapOf(
            succeeded to listOf(AiTestFixture.newsArticle()),
            failed to listOf(AiTestFixture.newsArticle())
        )
        llmProvider.failureByKeyword = mapOf(
            failed to AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(succeeded, failed), window = watermarkWindow())
        )

        assertEquals(AiRunStatus.PARTIALLY_FAILED, result.status)
        assertFalse(result.watermarkAdvanced)
        assertNull(watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY])
    }

    @Test
    @DisplayName("구간을 직접 지정한 수동 실행은 watermark를 전진시키지 않는다")
    fun keepWatermarkOnExplicitWindow() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = explicitWindow())
        )

        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertFalse(result.watermarkAdvanced)
        assertEquals(0, watermarkPersistence.saveCount)
    }

    @Test
    @DisplayName("격리된 키워드는 요약 대상과 watermark 전진 판단에서 모두 제외한다")
    fun excludeQuarantinedKeyword() = runBlocking {
        val healthy = AiKeyword.of("NVIDIA")
        val quarantined = AiKeyword.of("TESLA")
        quarantinePersistence.quarantines += AiTestFixture.quarantine(
            keyword = quarantined,
            consecutiveFailures = AiTestFixture.DEFAULT_QUARANTINE_FAILURE_THRESHOLD
        )
        newsReader.articlesByKeyword = mapOf(
            healthy to listOf(AiTestFixture.newsArticle()),
            quarantined to emptyList()
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(healthy, quarantined), window = watermarkWindow())
        )

        assertEquals(1, result.requestedKeywords)
        assertEquals(AiRunStatus.SUCCEEDED, result.status)
        assertTrue(result.watermarkAdvanced)
    }

    @Test
    @DisplayName("키워드 탓인 실패가 임계치에 도달하면 격리한다")
    fun quarantineKeywordAfterConsecutiveFailures() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        quarantinePersistence.quarantines += AiTestFixture.quarantine(keyword = keyword, consecutiveFailures = 2)
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        val quarantine = quarantinePersistence.findByKeyword(keyword)
        assertEquals(KeywordQuarantineStatus.QUARANTINED, quarantine?.status)
        assertEquals(AiTestFixture.DEFAULT_QUARANTINE_FAILURE_THRESHOLD, quarantine?.consecutiveFailures)
        assertEquals(AiFailureReason.INVALID_RESPONSE, quarantine?.lastFailureReason)
    }

    @Test
    @DisplayName("인프라 전역 실패는 연속 실패로 누적하지 않아 정상 키워드가 격리되지 않는다")
    fun doNotCountInfrastructureFailureTowardQuarantine() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        quarantinePersistence.quarantines += AiTestFixture.quarantine(keyword = keyword, consecutiveFailures = 2)
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_RATE_LIMITED)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        val quarantine = quarantinePersistence.findByKeyword(keyword)
        assertEquals(KeywordQuarantineStatus.TRACKING, quarantine?.status)
        assertEquals(2, quarantine?.consecutiveFailures)
        assertEquals(0, quarantinePersistence.saveCount)
        // 격리는 막되 실행은 실패로 남아 watermark가 유지된다. 다음 실행이 같은 구간을 다시 처리한다.
        assertEquals(1, result.failureCount)
        assertFalse(result.watermarkAdvanced)
        assertEquals(AiFailureReason.RATE_LIMITED, aiRunPersistence.savedRuns.last().failureReason)
    }

    @Test
    @DisplayName("LLM 실패 분류를 실행 기록의 실패 원인으로 남긴다")
    fun recordClassifiedFailureReason() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_NETWORK_ERROR)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(AiFailureReason.NETWORK_ERROR, aiRunPersistence.savedRuns.last().failureReason)
    }

    @Test
    @DisplayName("rate limit 한 건은 남은 키워드를 막지 않는다")
    fun continueRemainingKeywordsOnRateLimit() = runBlocking {
        val first = AiKeyword.of("NVIDIA")
        val second = AiKeyword.of("TESLA")
        newsReader.articlesByKeyword = mapOf(
            first to listOf(AiTestFixture.newsArticle()),
            second to listOf(AiTestFixture.newsArticle())
        )
        llmProvider.failureByKeyword = mapOf(
            first to AiTestFixture.llmProviderException(LlmFailureCode.LLM_RATE_LIMITED)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(first, second), window = watermarkWindow())
        )

        assertEquals(1, result.failureCount)
        assertEquals(1, result.succeededCount)
        assertEquals(0, result.skippedCount)
        assertEquals(2, llmProvider.summarizeCallCount)
        assertFalse(result.watermarkAdvanced)
    }

    @Test
    @DisplayName("호출할 수 있는 provider가 없으면 남은 키워드를 호출하지 않고 건너뛴다")
    fun abortRemainingKeywordsWhenNoProviderIsAvailable() = runBlocking {
        val first = AiKeyword.of("NVIDIA")
        val second = AiKeyword.of("TESLA")
        val third = AiKeyword.of("APPLE")
        newsReader.articlesByKeyword = mapOf(
            first to listOf(AiTestFixture.newsArticle()),
            second to listOf(AiTestFixture.newsArticle()),
            third to listOf(AiTestFixture.newsArticle())
        )
        llmProvider.failureByKeyword = mapOf(
            first to AiTestFixture.llmProviderException(LlmFailureCode.LLM_NOT_PERMITTED)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(first, second, third), window = watermarkWindow())
        )

        assertEquals(1, result.failureCount)
        assertEquals(2, result.skippedCount)
        assertEquals(AiSkipReason.PROVIDER_UNAVAILABLE, result.skipReason)
        assertEquals(1, llmProvider.summarizeCallCount)
        assertEquals(listOf(first), newsReader.readKeywords)
    }

    @Test
    @DisplayName("provider 불능 실패는 실행 기록에 provider 불능으로 남는다")
    fun recordProviderUnavailableFailureReason() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_NOT_PERMITTED)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(AiFailureReason.PROVIDER_UNAVAILABLE, aiRunPersistence.savedRuns.last().failureReason)
    }

    @Test
    @DisplayName("provider 불능은 watermark를 전진시키지 않는다")
    fun keepWatermarkOnProviderUnavailable() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_NOT_PERMITTED)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertFalse(result.watermarkAdvanced)
        assertEquals(0, watermarkPersistence.saveCount)
    }

    @Test
    @DisplayName("provider 불능은 키워드 격리 카운트를 올리지 않는다")
    fun doNotCountProviderUnavailableTowardQuarantine() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_NOT_PERMITTED)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(0, quarantinePersistence.saveCount)
        assertNull(quarantinePersistence.findByKeyword(keyword))
    }

    @Test
    @DisplayName("provider 불능 중에도 기존 요약이 있는 키워드는 재사용으로 성공한다")
    fun reuseExistingSummaryWhileProviderIsUnavailable() = runBlocking {
        val reusable = AiKeyword.of("NVIDIA")
        val failing = AiKeyword.of("TESLA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(
            reusable to listOf(article),
            failing to listOf(article)
        )
        newsSummaryPersistence.existingSummaries += AiTestFixture.newsSummary(
            keyword = reusable,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = reusable, sourceNewsIds = listOf(article.id))
        )
        llmProvider.failureByKeyword = mapOf(
            failing to AiTestFixture.llmProviderException(LlmFailureCode.LLM_NOT_PERMITTED)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(reusable, failing), window = watermarkWindow())
        )

        assertEquals(1, result.succeededCount)
        assertEquals(1, result.failureCount)
        assertEquals(true, result.summaries.single().reused)
        assertEquals(1, llmProvider.summarizeCallCount)
    }

    @Test
    @DisplayName("실제 호출이 있었던 실패는 남은 키워드 처리를 막지 않는다")
    fun continueRemainingKeywordsOnActualCallFailure() = runBlocking {
        val failed = AiKeyword.of("NVIDIA")
        val succeeded = AiKeyword.of("TESLA")
        newsReader.articlesByKeyword = mapOf(
            failed to listOf(AiTestFixture.newsArticle()),
            succeeded to listOf(AiTestFixture.newsArticle())
        )
        llmProvider.failureByKeyword = mapOf(
            failed to AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(failed, succeeded), window = watermarkWindow())
        )

        assertEquals(AiRunStatus.PARTIALLY_FAILED, result.status)
        assertEquals(1, result.succeededCount)
        assertEquals(1, result.failureCount)
        assertEquals(0, result.skippedCount)
        assertEquals(AiFailureReason.TIMEOUT, aiRunPersistence.savedRuns.last().failureReason)
    }

    @Test
    @DisplayName("시도한 후보 전부가 입력 탓으로 끝났을 때만 격리 카운트를 올린다")
    fun countQuarantineOnlyWhenEveryCandidateFailsByInput() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_REQUEST_REJECTED, LlmFailureCode.LLM_INVALID_RESPONSE)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(1, quarantinePersistence.findByKeyword(keyword)?.consecutiveFailures)
        assertEquals(AiFailureReason.INVALID_RESPONSE, quarantinePersistence.findByKeyword(keyword)?.lastFailureReason)
    }

    @Test
    @DisplayName("한 후보라도 입력 탓이 아니면 격리 카운트를 올리지 않는다")
    fun doNotCountQuarantineWhenAnyCandidateFailsOutsideInput() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE, LlmFailureCode.LLM_MODEL_NOT_FOUND)
        )
        val service = service()

        val result = service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(0, quarantinePersistence.saveCount)
        assertNull(quarantinePersistence.findByKeyword(keyword))
        assertEquals(1, result.failureCount)
        assertEquals(AiFailureReason.MODEL_NOT_FOUND, aiRunPersistence.savedRuns.last().failureReason)
    }

    @Test
    @DisplayName("모든 LLM 실패 코드에 대응하는 실패 원인이 정해져 있다")
    fun everyLlmFailureCodeHasExpectedReason() {
        assertEquals(LlmFailureCode.entries.toSet(), FAILURE_REASON_BY_CODE.keys)
    }

    @ParameterizedTest
    @EnumSource(LlmFailureCode::class)
    @DisplayName("LLM 실패 분류마다 대응하는 실패 원인으로 기록한다")
    fun mapEveryLlmFailureCodeToFailureReason(code: LlmFailureCode) = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val runs = FakeAiRunPersistencePort()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(keyword to AiTestFixture.llmProviderException(code))

        service(aiRunPersistence = runs).summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertEquals(FAILURE_REASON_BY_CODE.getValue(code), runs.savedRuns.last().failureReason)
    }

    @Test
    @DisplayName("뉴스 조회 실패는 서버 오류로 기록하고 키워드 격리 카운트를 올리지 않는다")
    fun recordNewsReaderFailureAsServerError() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.failureByKeyword = mapOf(
            keyword to NewsReaderException(NewsReaderErrorCode.COLLECTOR_SERVICE_REQUEST_FAILED)
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertEquals(1, result.failureCount)
        assertEquals(AiFailureReason.SERVER_ERROR, aiRunPersistence.savedRuns.last().failureReason)
        assertEquals(0, quarantinePersistence.saveCount)
    }

    @Test
    @DisplayName("도메인 불변식을 어긴 응답은 키워드 탓 실패로 격리 카운트를 올린다")
    fun countInvariantViolationTowardQuarantine() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(keyword to IllegalArgumentException("title is blank"))
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(AiFailureReason.INVALID_RESPONSE, aiRunPersistence.savedRuns.last().failureReason)
        assertEquals(1, quarantinePersistence.findByKeyword(keyword)?.consecutiveFailures)
    }

    @Test
    @DisplayName("분류할 수 없는 실패는 알 수 없는 원인으로 남기고 격리 카운트를 올리지 않는다")
    fun recordUnclassifiedFailureAsUnknown() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(keyword to IllegalStateException("boom"))
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(AiFailureReason.UNKNOWN, aiRunPersistence.savedRuns.last().failureReason)
        assertEquals(0, quarantinePersistence.saveCount)
    }

    @Test
    @DisplayName("활성 키워드를 읽지 못하면 실행 자체가 실패한다")
    fun failRunWhenActiveKeywordsCannotBeRead() = runBlocking {
        keywordReader.failure = KeywordReaderException(KeywordReaderErrorCode.USER_SERVICE_REQUEST_FAILED)
        val service = service()

        assertFailsWith<KeywordReaderException> {
            service.summarize(SummarizeNewsCommand(keywords = emptyList(), window = watermarkWindow()))
        }
        Unit
    }

    @Test
    @DisplayName("coroutine 취소는 요약 실패로 바꾸지 않고 그대로 전파한다")
    fun propagateCancellation() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(keyword to CancellationException("cancelled"))
        val service = service()

        assertFailsWith<CancellationException> {
            service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))
        }
        assertEquals(0, aiRunPersistence.savedRuns.count { it.failureCount > 0 })
    }

    @Test
    @DisplayName("watermark가 최대 조회 기간보다 오래되면 구간을 하한까지만 잡는다")
    fun truncateWindowByMaxLookback() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        watermarkPersistence.watermarks[AiRunTargetType.NEWS_SUMMARY] =
            AiTestFixture.watermark(position = now.minus(Duration.ofDays(2)))
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(now.minus(maxLookback), aiRunPersistence.savedRuns.last().windowFrom)
    }

    @Test
    @DisplayName("성공한 키워드의 연속 실패 누적을 되돌린다")
    fun resetFailuresWhenKeywordSucceeds() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        quarantinePersistence.quarantines += AiTestFixture.quarantine(keyword = keyword, consecutiveFailures = 2)
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(0, quarantinePersistence.findByKeyword(keyword)?.consecutiveFailures)
    }

    @Test
    @DisplayName("실패한 적 없는 키워드는 격리 기록을 만들지 않는다")
    fun doNotTrackKeywordWithoutFailure() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(0, quarantinePersistence.saveCount)
    }

    @Test
    @DisplayName("새 요약을 저장할 때 요약 생성 이벤트를 함께 넘긴다")
    fun recordSummaryCreatedEventWithNewSummary() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        val summary = newsSummaryPersistence.savedSummaries.single()
        val outbox = newsSummaryPersistence.savedOutboxes.single()
        assertEquals(AiOutboxEventType.SUMMARY_CREATED, outbox.eventType)
        assertEquals(summary.id.value.toString(), outbox.eventKey)
        assertEquals("NVIDIA", outbox.partitionKey)
        assertEquals(AiOutboxStatus.PENDING, outbox.status)
        assertEquals(now, outbox.createdAt)
        val event = assertIs<SummaryCreatedEvent>(eventSerializer.serialized.single())
        assertEquals(FakeAiOutboxEventSerializer.payloadOf(event), outbox.payload)
    }

    @Test
    @DisplayName("기존 요약을 재사용하면 이벤트를 만들지 않는다")
    fun doNotRecordEventWhenSummaryIsReused() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(article))
        newsSummaryPersistence.existingSummaries += AiTestFixture.newsSummary(
            keyword = keyword,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = keyword, sourceNewsIds = listOf(article.id))
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertTrue(eventSerializer.serialized.isEmpty())
        assertTrue(newsSummaryPersistence.savedOutboxes.isEmpty())
    }

    @Test
    @DisplayName("저장 중 중복이 발생하면 이벤트가 저장되지 않는다")
    fun doNotRecordEventOnDuplicateSave() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        val article = AiTestFixture.newsArticle()
        newsReader.articlesByKeyword = mapOf(keyword to listOf(article))
        newsSummaryPersistence.duplicateOnSave = true
        newsSummaryPersistence.existingAfterDuplicate = AiTestFixture.newsSummary(
            keyword = keyword,
            sourceNewsIds = listOf(article.id),
            newsHash = NewsHash.calculate(keyword = keyword, sourceNewsIds = listOf(article.id))
        )
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertEquals(true, result.summaries.first().reused)
        assertTrue(newsSummaryPersistence.savedOutboxes.isEmpty())
    }

    @Test
    @DisplayName("키워드가 격리로 넘어가면 격리 이벤트를 전이와 함께 저장한다")
    fun recordKeywordQuarantinedEventOnTransition() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        quarantinePersistence.quarantines += AiTestFixture.quarantine(keyword = keyword, consecutiveFailures = 2)
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(1, quarantinePersistence.saveQuarantinedCount)
        val quarantine = quarantinePersistence.findByKeyword(keyword)
        val outbox = quarantinePersistence.savedOutboxes.single()
        assertEquals(AiOutboxEventType.KEYWORD_QUARANTINED, outbox.eventType)
        assertEquals("${quarantine?.id?.value}:${now.toEpochMilli()}", outbox.eventKey)
        assertEquals("NVIDIA", outbox.partitionKey)
        val event = assertIs<KeywordQuarantinedEvent>(eventSerializer.serialized.single())
        assertEquals(FakeAiOutboxEventSerializer.payloadOf(event), outbox.payload)
    }

    @Test
    @DisplayName("이미 격리된 키워드는 대상에서 빠지므로 격리 이벤트를 다시 만들지 않는다")
    fun doNotRecordEventForAlreadyQuarantinedKeyword() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        quarantinePersistence.quarantines += AiTestFixture.quarantine(
            keyword = keyword,
            consecutiveFailures = AiTestFixture.DEFAULT_QUARANTINE_FAILURE_THRESHOLD
        )
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(0, quarantinePersistence.saveQuarantinedCount)
        assertTrue(quarantinePersistence.savedOutboxes.isEmpty())
    }

    @Test
    @DisplayName("임계치에 못 미친 실패는 격리 이벤트를 만들지 않는다")
    fun doNotRecordEventBelowFailureThreshold() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        llmProvider.failureByKeyword = mapOf(
            keyword to AiTestFixture.llmProviderException(LlmFailureCode.LLM_INVALID_RESPONSE)
        )
        val service = service()

        service.summarize(SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow()))

        assertEquals(1, quarantinePersistence.saveCount)
        assertEquals(0, quarantinePersistence.saveQuarantinedCount)
        assertTrue(quarantinePersistence.savedOutboxes.isEmpty())
    }

    @Test
    @DisplayName("이벤트 직렬화가 실패하면 요약을 저장하지 않고 그 키워드만 실패로 남긴다")
    fun failKeywordWhenEventSerializationFails() = runBlocking {
        val keyword = AiKeyword.of("NVIDIA")
        newsReader.articlesByKeyword = mapOf(keyword to listOf(AiTestFixture.newsArticle()))
        eventSerializer.failure = IllegalStateException("직렬화 실패")
        val service = service()

        val result = service.summarize(
            SummarizeNewsCommand(keywords = listOf(keyword), window = watermarkWindow())
        )

        assertEquals(1, result.failureCount)
        assertEquals(AiFailureReason.UNKNOWN, aiRunPersistence.savedRuns.last().failureReason)
        assertEquals(0, newsSummaryPersistence.saveOrFindExistingCallCount)
        // 서비스가 만든 실패이므로 키워드에 책임을 묻지 않는다.
        assertEquals(0, quarantinePersistence.saveCount)
    }

    private fun service(
        aiRunPersistence: FakeAiRunPersistencePort = this.aiRunPersistence
    ): SummarizeNewsService {
        return SummarizeNewsService(
            keywordReaderPort = keywordReader,
            newsReaderPort = newsReader,
            llmProviderPort = llmProvider,
            newsSummaryPersistencePort = newsSummaryPersistence,
            aiRunPersistencePort = aiRunPersistence,
            summaryWatermarkPersistencePort = watermarkPersistence,
            keywordQuarantinePersistencePort = quarantinePersistence,
            eventSerializer = eventSerializer,
            quarantinePolicy = AiTestFixture.storyQuarantinePolicy(),
            clock = clock
        )
    }

    private fun explicitWindow(
        from: Instant = AiTestFixture.NOW.minus(Duration.ofDays(1)),
        to: Instant = AiTestFixture.NOW
    ): ExplicitSummaryWindowRequest {
        return ExplicitSummaryWindowRequest(from = from, to = to)
    }

    private fun watermarkWindow(): WatermarkSummaryWindowRequest {
        return WatermarkSummaryWindowRequest(overlap = overlap, maxLookback = maxLookback)
    }

    private companion object {
        val FAILURE_REASON_BY_CODE = mapOf(
            LlmFailureCode.LLM_TIMEOUT to AiFailureReason.TIMEOUT,
            LlmFailureCode.LLM_RATE_LIMITED to AiFailureReason.RATE_LIMITED,
            LlmFailureCode.LLM_SERVER_ERROR to AiFailureReason.SERVER_ERROR,
            LlmFailureCode.LLM_NETWORK_ERROR to AiFailureReason.NETWORK_ERROR,
            LlmFailureCode.LLM_REQUEST_REJECTED to AiFailureReason.CLIENT_ERROR,
            LlmFailureCode.LLM_MODEL_NOT_FOUND to AiFailureReason.MODEL_NOT_FOUND,
            LlmFailureCode.LLM_UNAUTHORIZED to AiFailureReason.ACCOUNT_ERROR,
            LlmFailureCode.LLM_PAYMENT_REQUIRED to AiFailureReason.ACCOUNT_ERROR,
            LlmFailureCode.LLM_FORBIDDEN to AiFailureReason.ACCOUNT_ERROR,
            LlmFailureCode.LLM_INVALID_RESPONSE to AiFailureReason.INVALID_RESPONSE,
            LlmFailureCode.LLM_NOT_PERMITTED to AiFailureReason.PROVIDER_UNAVAILABLE,
            LlmFailureCode.LLM_UNKNOWN_ERROR to AiFailureReason.UNKNOWN
        )
    }
}
