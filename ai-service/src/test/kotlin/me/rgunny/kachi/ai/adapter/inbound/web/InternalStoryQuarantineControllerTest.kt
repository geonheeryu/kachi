package me.rgunny.kachi.ai.adapter.inbound.web

import me.rgunny.kachi.ai.adapter.inbound.web.response.ErrorCode
import me.rgunny.kachi.ai.application.exception.AiStoryQuarantineErrorCode
import me.rgunny.kachi.ai.application.exception.StoryQuarantineNotFoundException
import me.rgunny.kachi.ai.application.exception.StoryQuarantineNotReleasableException
import me.rgunny.kachi.ai.application.port.inbound.quarantine.model.StoryQuarantineSnapshot
import me.rgunny.kachi.ai.config.ApiVersionConfig
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.fake.RecordingFindStoryQuarantinesUseCase
import me.rgunny.kachi.ai.fake.RecordingReleaseStoryQuarantineUseCase
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.JsonBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * story 격리 조회·해제 API의 요청 변환과 오류 응답 규약을 보는 컨트롤러 슬라이스 테스트.
 */
@WebFluxTest(controllers = [InternalStoryQuarantineController::class])
@Import(ApiVersionConfig::class, InternalStoryQuarantineControllerTest.TestBeans::class)
@DisplayName("InternalStoryQuarantineController")
class InternalStoryQuarantineControllerTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var findUseCase: RecordingFindStoryQuarantinesUseCase

    @Autowired
    private lateinit var releaseUseCase: RecordingReleaseStoryQuarantineUseCase

    @BeforeEach
    fun resetUseCases() {
        // 테스트끼리 공유하는 컨트롤러 슬라이스 컨텍스트의 호출 기록 초기화
        findUseCase.invokeCount = 0
        findUseCase.lastQuery = null
        findUseCase.quarantines = emptyList()
        releaseUseCase.invokeCount = 0
        releaseUseCase.lastCommand = null
        releaseUseCase.failure = null
    }

    @Test
    @DisplayName("격리 기록 목록을 응답으로 옮기고 상태 조건을 그대로 전달한다")
    fun findQuarantines() {
        findUseCase.quarantines = listOf(
            StoryQuarantineSnapshot.from(
                AiTestFixture.storyQuarantine(consecutiveFailures = AiTestFixture.DEFAULT_QUARANTINE_FAILURE_THRESHOLD)
            )
        )

        val body = webTestClient.get()
            .uri { builder ->
                builder.path(ApiPaths.V1_INTERNAL_AI_STORY_QUARANTINES)
                    .queryParam("status", StoryQuarantineStatus.QUARANTINED.name)
                    .build()
            }
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody

        val json = JsonBody.parse(body)
        assertTrue(json.get("success").asBoolean())
        val quarantine = json.get("data").get(0)
        assertEquals(AiTestFixture.STORY_ID.value.toString(), quarantine.get("storyId").asString())
        assertEquals(StoryQuarantineStatus.QUARANTINED.name, quarantine.get("status").asString())
        assertEquals(StoryQuarantineStatus.QUARANTINED, requireNotNull(findUseCase.lastQuery).status)
    }

    @Test
    @DisplayName("격리를 해제하면 해제된 기록을 응답한다")
    fun releaseQuarantine() {
        val body = webTestClient.post()
            .uri(ApiPaths.V1_INTERNAL_AI_STORY_QUARANTINE_RELEASE, AiTestFixture.STORY_ID.value)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody

        val json = JsonBody.parse(body)
        assertTrue(json.get("success").asBoolean())
        assertEquals(StoryQuarantineStatus.RELEASED.name, json.get("data").get("status").asString())
        assertEquals(AiTestFixture.STORY_ID, requireNotNull(releaseUseCase.lastCommand).storyId)
    }

    @Test
    @DisplayName("해제 대상 기록이 없으면 404와 story 격리 코드로 응답한다")
    fun releaseMissingQuarantine() {
        releaseUseCase.failure = StoryQuarantineNotFoundException(AiTestFixture.STORY_ID)

        assertFailureResponse(
            errorCode = ErrorCode.STORY_QUARANTINE_NOT_FOUND,
            applicationCode = AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_FOUND
        )
    }

    @Test
    @DisplayName("격리 상태가 아니면 409와 story 격리 코드로 응답한다")
    fun releaseNotQuarantinedStory() {
        releaseUseCase.failure = StoryQuarantineNotReleasableException(
            storyId = AiTestFixture.STORY_ID,
            status = StoryQuarantineStatus.RELEASED
        )

        assertFailureResponse(
            errorCode = ErrorCode.STORY_QUARANTINE_NOT_RELEASABLE,
            applicationCode = AiStoryQuarantineErrorCode.STORY_QUARANTINE_NOT_RELEASABLE
        )
    }

    private fun assertFailureResponse(errorCode: ErrorCode, applicationCode: AiStoryQuarantineErrorCode) {
        val body = webTestClient.post()
            .uri(ApiPaths.V1_INTERNAL_AI_STORY_QUARANTINE_RELEASE, AiTestFixture.STORY_ID.value)
            .exchange()
            .expectStatus().isEqualTo(errorCode.status)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody

        val json = JsonBody.parse(body)
        assertEquals(false, json.get("success").asBoolean())
        assertEquals(errorCode.name, json.get("error").get("code").asString())
        assertTrue(
            json.get("error").get("message").asString()
                .startsWith("${applicationCode.code} ${applicationCode.message}: storyId=${AiTestFixture.STORY_ID.value}")
        )
    }

    @TestConfiguration
    class TestBeans {

        @Bean
        fun recordingFindStoryQuarantinesUseCase() = RecordingFindStoryQuarantinesUseCase()

        @Bean
        fun recordingReleaseStoryQuarantineUseCase() = RecordingReleaseStoryQuarantineUseCase()
    }
}
