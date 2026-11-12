package me.rgunny.kachi.ai.adapter.outbound.persistence.run

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.ai.domain.llm.LlmProvider
import me.rgunny.kachi.ai.domain.llm.PromptVersion
import me.rgunny.kachi.ai.domain.run.AiFailureReason
import me.rgunny.kachi.ai.domain.run.AiRun
import me.rgunny.kachi.ai.domain.run.AiRunStatus
import me.rgunny.kachi.ai.domain.run.AiRunTargetType
import me.rgunny.kachi.ai.fixture.AiTestFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@DisplayName("AiRunPersistenceAdapter 통합 테스트")
class AiRunPersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var adapter: AiRunPersistenceAdapter

    @Autowired
    private lateinit var repository: AiRunMongoRepository

    private val startedAt = AiTestFixture.NOW
    private val finishedAt = AiTestFixture.NOW.plusSeconds(5)

    @BeforeEach
    fun cleanUp() {
        repository.deleteAll().block()
    }

    @Nested
    @DisplayName("save()")
    inner class Save {

        @Test
        @DisplayName("AiRun 도메인을 MongoDB에 저장하고 다시 조회한다")
        fun saveAiRunAndFindById() = runBlocking {
            val completedRun = AiRun.start(
                targetType = AiRunTargetType.KEYWORD_EXPANSION,
                requestedKeywords = 2,
                startedAt = startedAt
            ).complete(
                succeededCount = 1,
                failureCount = 1,
                failureReason = AiFailureReason.INVALID_RESPONSE,
                provider = LlmProvider.OPENROUTER,
                model = "openai/gpt-4o-mini",
                promptVersion = PromptVersion.of("keyword-expansion-v1"),
                finishedAt = finishedAt
            )

            val saved = adapter.save(completedRun)
            val found = adapter.findById(saved.id)

            assertNotNull(found)
            assertEquals(saved.id, found.id)
            assertEquals(AiRunStatus.PARTIALLY_FAILED, found.status)
            assertEquals(AiRunTargetType.KEYWORD_EXPANSION, found.targetType)
            assertEquals(2, found.requestedKeywords)
            assertEquals(1, found.succeededCount)
            assertEquals(1, found.failureCount)
            assertEquals(AiFailureReason.INVALID_RESPONSE, found.failureReason)
            assertEquals(LlmProvider.OPENROUTER, found.provider)
            assertEquals("openai/gpt-4o-mini", found.model)
            assertEquals(PromptVersion.of("keyword-expansion-v1"), found.promptVersion)
        }
    }

    @Nested
    @DisplayName("findById()")
    inner class FindById {

        @Test
        @DisplayName("AI 실행 기록이 없으면 null을 반환한다")
        fun returnNullWhenAiRunDoesNotExist() = runBlocking {
            val run = AiRun.start(
                targetType = AiRunTargetType.KEYWORD_EXPANSION,
                requestedKeywords = 0,
                startedAt = startedAt
            )

            val found = adapter.findById(run.id)

            assertNull(found)
        }
    }
}
