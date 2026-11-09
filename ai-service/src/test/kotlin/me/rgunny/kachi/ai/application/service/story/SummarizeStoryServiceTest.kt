package me.rgunny.kachi.ai.application.service.story

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.application.exception.LlmProviderException
import me.rgunny.kachi.ai.application.port.inbound.story.model.CreatedStorySummaryResult
import me.rgunny.kachi.ai.application.port.inbound.story.model.FailedStorySummaryResult
import me.rgunny.kachi.ai.application.port.inbound.story.model.SkippedStorySummaryResult
import me.rgunny.kachi.ai.application.port.inbound.story.model.StorySummarySkipReason
import me.rgunny.kachi.ai.application.port.inbound.story.model.SummarizeStoryCommand
import me.rgunny.kachi.ai.domain.keyword.AiKeyword
import me.rgunny.kachi.ai.domain.llm.LlmFailureCode
import me.rgunny.kachi.ai.domain.outbox.AiOutboxEventType
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.story.AiStory
import me.rgunny.kachi.ai.domain.story.StoryId
import me.rgunny.kachi.ai.domain.summary.StoryDevelopmentKind
import me.rgunny.kachi.ai.fake.FakeAiOutboxEventSerializer
import me.rgunny.kachi.ai.fake.FakeLlmProviderPort
import me.rgunny.kachi.ai.fake.FakeStoryQuarantinePersistencePort
import me.rgunny.kachi.ai.fake.InMemoryStoryStore
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SummarizeStoryService")
class SummarizeStoryServiceTest {

    private val store = InMemoryStoryStore()
    private val quarantines = FakeStoryQuarantinePersistencePort()
    private val llm = FakeLlmProviderPort()
    private val serializer = FakeAiOutboxEventSerializer()

    private fun service(
        maxArticlesPerVersion: Int = 50
    ): SummarizeStoryService {
        return SummarizeStoryService(
            aiStoryPersistencePort = store,
            aiStoryArticlePersistencePort = store,
            storySummaryPersistencePort = store,
            storyQuarantinePersistencePort = quarantines,
            llmProviderPort = llm,
            eventSerializer = serializer,
            policy = AiTestFixture.storySummaryPolicy(maxArticlesPerVersion = maxArticlesPerVersion),
            quarantinePolicy = AiTestFixture.storyQuarantinePolicy(),
            clock = AiTestFixture.CLOCK
        )
    }

    /**
     * 미요약 기사 [pending]건을 가진 story를 저장 상태에 만든다.
     */
    private fun seedStory(
        storyId: StoryId = AiTestFixture.STORY_ID,
        pending: Int = 3
    ): AiStory = runBlocking {
        var story = AiTestFixture.aiStory(storyId = storyId, attachedAt = AiTestFixture.NOW)
        store.stories[storyId] = story
        (1..pending).forEach { index ->
            store.articles[newsId(index)] = AiTestFixture.storyArticle(
                newsId = newsId(index),
                storyId = storyId,
                title = "기사 $index",
                attachedAt = AiTestFixture.NOW.plusSeconds(index.toLong())
            )
            if (index > 1) {
                story = story.accept(
                    keywords = listOf(AiKeyword.of("NVIDIA")),
                    articleCount = index,
                    attachedAt = AiTestFixture.NOW.plusSeconds(index.toLong()),
                    now = AiTestFixture.NOW.plusSeconds(index.toLong())
                )
                store.stories[storyId] = story
            }
        }

        story
    }

    private fun newsId(index: Int): UUID = UUID.fromString("018f0000-0000-7000-8000-00000000010$index")

    private fun summarize(service: SummarizeStoryService = service()) = runBlocking {
        service.summarize(SummarizeStoryCommand(storyId = AiTestFixture.STORY_ID))
    }

    @Nested
    @DisplayName("버전 생성")
    inner class CreateVersion {

        @Test
        @DisplayName("미요약 기사 전부로 첫 버전을 만들고 발행 대기 이벤트를 남긴다")
        fun createFirstVersion() {
            seedStory(pending = 3)

            val result = summarize() as CreatedStorySummaryResult

            assertEquals(1, result.version)
            assertEquals(3, result.newArticleCount)
            assertTrue(result.published)
            val summary = store.summaries.single()
            assertEquals(3, summary.sourceNewsCount)
            assertEquals(StoryDevelopmentKind.DEVELOPMENT, summary.developmentKind)
            assertEquals(AiOutboxEventType.SUMMARY_CREATED, store.outboxes.single().eventType)
            assertEquals(0, store.stories.getValue(AiTestFixture.STORY_ID).pendingCount)
            assertTrue(store.articles.values.none { it.pending })
        }

        @Test
        @DisplayName("첫 버전은 LLM 응답이 무엇이든 DEVELOPMENT로 저장한다")
        fun forceDevelopmentOnFirstVersion() {
            seedStory(pending = 3)
            llm.storyDevelopmentKind = StoryDevelopmentKind.NO_CHANGE

            val result = summarize() as CreatedStorySummaryResult

            assertEquals(StoryDevelopmentKind.DEVELOPMENT, result.developmentKind)
        }

        @Test
        @DisplayName("직전 버전이 있으면 그 요약을 입력에 싣고 누적 수를 이어 센다")
        fun buildOnPreviousVersion() {
            val story = seedStory(pending = 3)
            store.summaries += AiTestFixture.storySummary(version = 1, sourceNewsCount = 5)
            store.stories[AiTestFixture.STORY_ID] = AiStory.restore(
                storyId = story.storyId,
                keywords = story.keywords,
                articleCount = story.articleCount,
                latestVersion = 1,
                latestVersionAt = AiTestFixture.NOW,
                pendingCount = story.pendingCount,
                oldestPendingAt = story.oldestPendingAt,
                mergedInto = null,
                version = story.version,
                updatedAt = story.updatedAt
            )

            val result = summarize() as CreatedStorySummaryResult

            assertEquals(2, result.version)
            assertEquals("story 요약 v1", llm.lastStoryPreviousSummary?.title)
            assertEquals(8, store.summaries.last().sourceNewsCount)
        }

        @Test
        @DisplayName("버전 하나에 싣는 새 기사는 오래된 순으로 상한까지다")
        fun capArticlesPerVersion() {
            seedStory(pending = 5)

            val result = summarize(service(maxArticlesPerVersion = 3)) as CreatedStorySummaryResult

            assertEquals(3, result.newArticleCount)
            val state = store.stories.getValue(AiTestFixture.STORY_ID)
            assertEquals(2, state.pendingCount)
            assertEquals(AiTestFixture.NOW.plusSeconds(4), state.oldestPendingAt)
            assertEquals(listOf("기사 1", "기사 2", "기사 3"), llm.lastStoryArticles.map { it.title })
        }
    }

    @Nested
    @DisplayName("전개 종류별 발행")
    inner class PublishByKind {

        @Test
        @DisplayName("DEVELOPMENT는 버전과 요약 생성 이벤트 하나를 함께 남긴다")
        fun developmentPublishesSummaryCreated() {
            seedStory(pending = 3)
            store.summaries += AiTestFixture.storySummary(version = 1)
            markLatestVersion()
            llm.storyDevelopmentKind = StoryDevelopmentKind.DEVELOPMENT

            val result = summarize() as CreatedStorySummaryResult

            assertTrue(result.published)
            assertEquals(2, store.summaries.size)
            assertEquals(AiOutboxEventType.SUMMARY_CREATED, store.outboxes.single().eventType)
        }

        @Test
        @DisplayName("NO_CHANGE는 버전만 남기고 이벤트를 남기지 않는다")
        fun noChangeSkipsPublish() {
            seedStory(pending = 3)
            store.summaries += AiTestFixture.storySummary(version = 1)
            markLatestVersion()
            llm.storyDevelopmentKind = StoryDevelopmentKind.NO_CHANGE

            val result = summarize() as CreatedStorySummaryResult

            assertFalse(result.published)
            assertEquals(2, store.summaries.size)
            assertTrue(store.outboxes.isEmpty())
        }

        @Test
        @DisplayName("NEW_STORY는 분리 요청 이벤트만 남긴다")
        fun newStoryPublishesSplitRequest() {
            seedStory(pending = 3)
            store.summaries += AiTestFixture.storySummary(version = 1)
            markLatestVersion()
            llm.storyDevelopmentKind = StoryDevelopmentKind.NEW_STORY

            val result = summarize() as CreatedStorySummaryResult

            assertTrue(result.published)
            assertEquals(2, store.summaries.size)
            assertEquals(AiOutboxEventType.STORY_SPLIT_REQUESTED, store.outboxes.single().eventType)
        }

        private fun markLatestVersion() {
            val story = store.stories.getValue(AiTestFixture.STORY_ID)
            store.stories[AiTestFixture.STORY_ID] = AiStory.restore(
                storyId = story.storyId,
                keywords = story.keywords,
                articleCount = story.articleCount,
                latestVersion = 1,
                latestVersionAt = AiTestFixture.NOW,
                pendingCount = story.pendingCount,
                oldestPendingAt = story.oldestPendingAt,
                mergedInto = null,
                version = story.version,
                updatedAt = story.updatedAt
            )
        }
    }

    @Nested
    @DisplayName("건너뛰기")
    inner class Skip {

        @Test
        @DisplayName("격리된 story는 LLM을 부르지 않고 끝낸다")
        fun skipQuarantined() {
            seedStory(pending = 3)
            quarantines.quarantines[AiTestFixture.STORY_ID] = AiTestFixture.storyQuarantine(consecutiveFailures = 3)

            val result = summarize() as SkippedStorySummaryResult

            assertEquals(StorySummarySkipReason.QUARANTINED, result.reason)
            assertEquals(0, llm.summarizeStoryCallCount)
        }

        @Test
        @DisplayName("모르는 story나 미요약 없는 story는 NO_PENDING이다")
        fun skipWithoutPending() {
            val result = summarize() as SkippedStorySummaryResult

            assertEquals(StorySummarySkipReason.NO_PENDING, result.reason)
        }

        @Test
        @DisplayName("저장 경합에서 지면 이번 결과를 버리고 SUPERSEDED로 끝낸다")
        fun supersededOnConflict() {
            seedStory(pending = 3)
            store.saveVersionResult = false

            val result = summarize() as SkippedStorySummaryResult

            assertEquals(StorySummarySkipReason.SUPERSEDED, result.reason)
            assertEquals(1, llm.summarizeStoryCallCount)
            assertTrue(store.summaries.isEmpty())
        }
    }

    @Nested
    @DisplayName("실패 분류")
    inner class Failure {

        @Test
        @DisplayName("전 후보가 입력 탓이면 격리 카운트를 올리고 결과로 끝낸다")
        fun countStoryBoundFailure() {
            seedStory(pending = 3)
            llm.storyFailure = AiTestFixture.llmProviderException(LlmFailureCode.LLM_REQUEST_REJECTED)

            val result = summarize() as FailedStorySummaryResult

            assertEquals(AiFailureReason.CLIENT_ERROR, result.reason)
            assertFalse(result.quarantined)
            assertEquals(1, quarantines.quarantines.getValue(AiTestFixture.STORY_ID).consecutiveFailures)
        }

        @Test
        @DisplayName("임계치에 도달하면 격리하고 발행 대기 이벤트를 함께 남긴다")
        fun quarantineAtThreshold() {
            seedStory(pending = 3)
            quarantines.quarantines[AiTestFixture.STORY_ID] = AiTestFixture.storyQuarantine(consecutiveFailures = 2)
            llm.storyFailure = AiTestFixture.llmProviderException(LlmFailureCode.LLM_REQUEST_REJECTED)

            val result = summarize() as FailedStorySummaryResult

            assertTrue(result.quarantined)
            assertTrue(quarantines.quarantines.getValue(AiTestFixture.STORY_ID).isQuarantined)
            assertEquals(AiOutboxEventType.STORY_QUARANTINED, quarantines.quarantinedOutboxes.single().eventType)
        }

        @Test
        @DisplayName("임계치 전의 실패는 격리 이벤트를 남기지 않는다")
        fun noQuarantineEventBelowThreshold() {
            seedStory(pending = 3)
            quarantines.quarantines[AiTestFixture.STORY_ID] = AiTestFixture.storyQuarantine(consecutiveFailures = 1)
            llm.storyFailure = AiTestFixture.llmProviderException(LlmFailureCode.LLM_REQUEST_REJECTED)

            val result = summarize() as FailedStorySummaryResult

            assertFalse(result.quarantined)
            assertTrue(quarantines.quarantinedOutboxes.isEmpty())
        }

        @Test
        @DisplayName("인프라 실패는 격리 카운트 없이 예외로 올린다")
        fun rethrowTransientFailure() {
            seedStory(pending = 3)
            llm.storyFailure = AiTestFixture.llmProviderException(LlmFailureCode.LLM_TIMEOUT)

            assertFailsWith<LlmProviderException> { summarize() }

            assertTrue(quarantines.quarantines.isEmpty())
        }

        @Test
        @DisplayName("성공하면 쌓인 실패 누적을 0으로 되돌린다")
        fun resetFailuresOnSuccess() {
            seedStory(pending = 3)
            quarantines.quarantines[AiTestFixture.STORY_ID] = AiTestFixture.storyQuarantine(consecutiveFailures = 2)

            summarize()

            assertEquals(0, quarantines.quarantines.getValue(AiTestFixture.STORY_ID).consecutiveFailures)
        }
    }
}
