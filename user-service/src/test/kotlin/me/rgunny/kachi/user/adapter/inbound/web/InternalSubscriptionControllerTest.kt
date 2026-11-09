package me.rgunny.kachi.user.adapter.inbound.web

import me.rgunny.kachi.user.adapter.inbound.web.fake.FakeFindSubscriptionRecipientsUseCase
import me.rgunny.kachi.user.adapter.inbound.web.fake.WebMvcFakeUseCaseConfig
import me.rgunny.kachi.user.application.port.inbound.internal.FindSubscribersUseCase
import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscribersQuery
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriberResult
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientKeywordResult
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientPageResult
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientResult
import me.rgunny.kachi.user.config.ApiVersionConfig
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@WebMvcTest(controllers = [InternalSubscriptionController::class])
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(
    SecurityAutoConfiguration::class,
    ServletWebSecurityAutoConfiguration::class,
    SecurityFilterAutoConfiguration::class
)
@Import(ApiVersionConfig::class, WebMvcFakeUseCaseConfig::class)
@DisplayName("InternalSubscriptionController")
class InternalSubscriptionControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val findSubscriptionRecipientsUseCase: FakeFindSubscriptionRecipientsUseCase
) {

    @BeforeEach
    fun setUp() {
        findSubscriptionRecipientsUseCase.result = SubscriptionRecipientPageResult.empty()
        findSubscriptionRecipientsUseCase.query = null
    }

    @Test
    @DisplayName("키워드를 질의로 옮기고 수신자를 id 문자열로 돌려준다")
    fun mapSubscribers() {
        val userId = UserId.newId()
        var received: FindSubscribersQuery? = null
        val controller = InternalSubscriptionController(
            object : FindSubscribersUseCase {
                override fun findSubscribers(query: FindSubscribersQuery): List<SubscriberResult> {
                    received = query
                    return listOf(SubscriberResult(userId, SubscriptionChannel.TELEGRAM))
                }
            },
            findSubscriptionRecipientsUseCase
        )

        val response = controller.findSubscribers("tesla")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("tesla", received?.keyword)
        val body = response.body?.data?.single()
        assertEquals(userId.value.toString(), body?.userId)
        assertEquals(SubscriptionChannel.TELEGRAM, body?.channel)
    }

    @Nested
    @DisplayName("findSubscriptionRecipients()")
    inner class FindSubscriptionRecipients {

        @Test
        @DisplayName("요청을 질의로 옮기고 키워드·수신자·nextCursor를 응답 형태로 돌려준다")
        fun mapRecipientPage() {
            val cursor = UserId.newId()
            val userId = UserId.newId()
            findSubscriptionRecipientsUseCase.result = SubscriptionRecipientPageResult(
                keywords = listOf(
                    SubscriptionRecipientKeywordResult(canonicalKey = "nvidia", displayName = "NVIDIA"),
                    SubscriptionRecipientKeywordResult(canonicalKey = "tsmc", displayName = "TSMC")
                ),
                recipients = listOf(SubscriptionRecipientResult(userId, SubscriptionChannel.SLACK, listOf(0, 1))),
                nextCursor = userId
            )

            val response = search("""{"keywords": ["nvidia", "TSMC"], "cursor": "${cursor.value}", "limit": 50}""")

            assertEquals(HttpStatus.OK.value(), response.status)
            assertEquals(
                """{"success":true,"data":{""" +
                    """"keywords":[{"canonicalKey":"nvidia","displayName":"NVIDIA"},{"canonicalKey":"tsmc","displayName":"TSMC"}],""" +
                    """"recipients":[{"userId":"${userId.value}","channel":"SLACK","keywordIndexes":[0,1]}],""" +
                    """"nextCursor":"${userId.value}"},"error":null}""",
                response.contentAsString
            )
            val query = findSubscriptionRecipientsUseCase.query!!
            assertEquals(listOf("nvidia", "TSMC"), query.keywords)
            assertEquals(cursor, query.cursor)
            assertEquals(50, query.limit)
        }

        @Test
        @DisplayName("cursor가 null이고 limit을 생략하면 첫 페이지 100명이며 마지막 페이지의 nextCursor는 null이다")
        fun defaultLimitAndNullCursor() {
            val response = search("""{"keywords": ["nvidia"], "cursor": null}""")

            assertEquals(HttpStatus.OK.value(), response.status, response.contentAsString)
            assertEquals(
                """{"success":true,"data":{"keywords":[],"recipients":[],"nextCursor":null},"error":null}""",
                response.contentAsString
            )
            val query = findSubscriptionRecipientsUseCase.query!!
            assertNull(query.cursor)
            assertEquals(100, query.limit)
        }

        @Test
        @DisplayName("limit이 상한 100을 넘으면 400이다")
        fun rejectLimitOverMax() {
            expectInvalidRequest("""{"keywords": ["nvidia"], "cursor": null, "limit": 101}""")
        }

        @Test
        @DisplayName("limit이 0이면 400이다")
        fun rejectZeroLimit() {
            expectInvalidRequest("""{"keywords": ["nvidia"], "cursor": null, "limit": 0}""")
        }

        @Test
        @DisplayName("cursor가 UUID 형식이 아니면 400이다")
        fun rejectMalformedCursor() {
            expectInvalidRequest("""{"keywords": ["nvidia"], "cursor": "not-a-uuid", "limit": 10}""")
        }

        @Test
        @DisplayName("keywords가 비어 있으면 400이다")
        fun rejectEmptyKeywords() {
            expectInvalidRequest("""{"keywords": [], "cursor": null, "limit": 10}""")
        }

        @Test
        @DisplayName("keywords가 100개를 넘으면 400이다")
        fun rejectTooManyKeywords() {
            val keywords = (1..101).joinToString(", ") { "\"k$it\"" }

            expectInvalidRequest("""{"keywords": [$keywords], "cursor": null, "limit": 10}""")
        }

        @Test
        @DisplayName("blank 키워드가 있으면 400이다")
        fun rejectBlankKeyword() {
            expectInvalidRequest("""{"keywords": ["nvidia", "  "], "cursor": null, "limit": 10}""")
        }

        @Test
        @DisplayName("null 키워드가 있으면 400이다")
        fun rejectNullKeyword() {
            expectInvalidRequest("""{"keywords": ["nvidia", null], "cursor": null, "limit": 10}""")
        }

        @Test
        @DisplayName("정규화하면 빈 값이 되는 키워드가 있으면 400이다")
        fun rejectKeywordNormalizedToEmpty() {
            expectInvalidRequest("""{"keywords": ["nvidia", "!!!"], "cursor": null, "limit": 10}""")
        }

        @Test
        @DisplayName("정규화한 키워드가 100자를 넘으면 400이다")
        fun rejectKeywordOverMaxLength() {
            expectInvalidRequest("""{"keywords": ["${"a".repeat(101)}"], "cursor": null, "limit": 10}""")
        }

        private fun expectInvalidRequest(body: String) {
            val response = search(body)

            assertEquals(HttpStatus.BAD_REQUEST.value(), response.status, response.contentAsString)
            assertTrue(
                response.contentAsString.startsWith("""{"success":false,"data":null,"error":{"code":"INVALID_REQUEST","""),
                response.contentAsString
            )
        }

        private fun search(body: String): MockHttpServletResponse {
            return mockMvc.post("/api/v1/internal/subscriptions/recipients/search") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn().response
        }
    }
}
