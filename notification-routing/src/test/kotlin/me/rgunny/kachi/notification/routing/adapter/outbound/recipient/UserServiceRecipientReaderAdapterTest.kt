package me.rgunny.kachi.notification.routing.adapter.outbound.recipient

import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.notification.contract.NotificationChannel
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.Recipient
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.RecipientPage
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedKeyword
import me.rgunny.kachi.notification.routing.application.port.outbound.recipient.model.SubscribedRecipient
import me.rgunny.kachi.notification.routing.exception.routing.RecipientReaderException
import me.rgunny.kachi.notification.routing.exception.routing.RoutingErrorCode
import me.rgunny.kachi.notification.routing.support.CapturingExchangeFunction
import me.rgunny.kachi.notification.routing.support.RoutingTestFixture
import me.rgunny.kachi.notification.routing.support.jsonExchangeFunction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("UserServiceRecipientReaderAdapter")
class UserServiceRecipientReaderAdapterTest {

    private val subscribersJson = """
        {
          "success": true,
          "data": {
            "keywords": [
              { "canonicalKey": "nvidia", "displayName": "NVIDIA" },
              { "canonicalKey": "tsmc", "displayName": "TSMC" }
            ],
            "recipients": [
              { "userId": "user-1", "channel": "SLACK", "keywordIndexes": [0, 1] },
              { "userId": "user-1", "channel": "TELEGRAM", "keywordIndexes": [1] },
              { "userId": "user-2", "channel": "DISCORD", "keywordIndexes": [0] }
            ],
            "nextCursor": "user-2"
          },
          "error": null
        }
    """.trimIndent()

    private val adminsJson = """
        {
          "success": true,
          "data": [
            { "userId": "admin-1", "channels": ["SLACK", "TELEGRAM"] },
            { "userId": "admin-2", "channels": ["DISCORD"] }
          ]
        }
    """.trimIndent()

    @Nested
    @DisplayName("구독자 조회")
    inner class Subscribers {

        @Test
        @DisplayName("수신자 조회 경로로 keywords, cursor, 설정의 page-size를 POST body에 실어 호출한다")
        fun requestBody() = runBlocking {
            val exchange = CapturingExchangeFunction(subscribersJson)

            adapterOf(exchange, pageSize = 50).findSubscribers(linkedSetOf("nvidia", "tsmc"), "user-0")

            assertEquals(HttpMethod.POST, exchange.request.method())
            assertEquals(RoutingTestFixture.RECIPIENTS_SEARCH_PATH, exchange.request.url().path)
            assertNull(exchange.request.url().rawQuery)
            assertEquals(
                JsonMapper.builder().build().readTree("""{ "keywords": ["nvidia", "tsmc"], "cursor": "user-0", "limit": 50 }"""),
                exchange.sentBody,
            )
        }

        @Test
        @DisplayName("첫 페이지는 cursor를 null로 보낸다")
        fun firstPageCursor() = runBlocking {
            val exchange = CapturingExchangeFunction(subscribersJson)

            adapterOf(exchange).findSubscribers(setOf("nvidia"), null)

            val body = exchange.sentBody
            assertTrue(body.get("cursor").isNull)
            assertEquals(100, body.get("limit").asInt())
        }

        @Test
        @DisplayName("keywordIndexes를 응답 keywords로 풀고 채널을 계약 채널로 짝지으며 nextCursor를 전달한다")
        fun mapPage() = runBlocking {
            val page = adapterOf(jsonExchangeFunction(subscribersJson)).findSubscribers(setOf("nvidia", "tsmc"), null)

            val nvidia = SubscribedKeyword("nvidia", "NVIDIA")
            val tsmc = SubscribedKeyword("tsmc", "TSMC")
            assertEquals(
                RecipientPage(
                    recipients = listOf(
                        SubscribedRecipient("user-1", NotificationChannel.SLACK, listOf(nvidia, tsmc)),
                        SubscribedRecipient("user-1", NotificationChannel.TELEGRAM, listOf(tsmc)),
                        SubscribedRecipient("user-2", NotificationChannel.DISCORD, listOf(nvidia)),
                    ),
                    nextCursor = "user-2",
                ),
                page,
            )
        }

        @Test
        @DisplayName("수신자가 0명이어도 nextCursor를 전달한다")
        fun emptyPageWithNextCursor() = runBlocking {
            val body = pageJson(keywords = """[]""", recipients = """[]""", nextCursor = "\"user-9\"")

            val page = adapterOf(jsonExchangeFunction(body)).findSubscribers(setOf("nobody"), null)

            assertTrue(page.recipients.isEmpty())
            assertEquals("user-9", page.nextCursor)
        }

        @Test
        @DisplayName("마지막 페이지의 nextCursor는 null이다")
        fun lastPage() = runBlocking {
            val body = pageJson(keywords = """[]""", recipients = """[]""", nextCursor = "null")

            val page = adapterOf(jsonExchangeFunction(body)).findSubscribers(setOf("nobody"), "user-9")

            assertTrue(page.recipients.isEmpty())
            assertNull(page.nextCursor)
        }

        @Test
        @DisplayName("범위 밖 keywordIndexes는 USER_SERVICE_RESPONSE_INVALID다")
        fun rejectOutOfRangeIndex() = runBlocking {
            val body = pageJson(recipients = """[{ "userId": "user-1", "channel": "SLACK", "keywordIndexes": [1] }]""")

            assertResponseInvalid(body)
        }

        @Test
        @DisplayName("음수 keywordIndexes는 USER_SERVICE_RESPONSE_INVALID다")
        fun rejectNegativeIndex() = runBlocking {
            val body = pageJson(recipients = """[{ "userId": "user-1", "channel": "SLACK", "keywordIndexes": [-1] }]""")

            assertResponseInvalid(body)
        }

        @Test
        @DisplayName("빈 keywordIndexes는 USER_SERVICE_RESPONSE_INVALID다")
        fun rejectEmptyIndexes() = runBlocking {
            val body = pageJson(recipients = """[{ "userId": "user-1", "channel": "SLACK", "keywordIndexes": [] }]""")

            assertResponseInvalid(body)
        }

        @Test
        @DisplayName("모르는 채널은 USER_SERVICE_RESPONSE_INVALID다")
        fun rejectUnknownChannel() = runBlocking {
            val body = pageJson(recipients = """[{ "userId": "user-1", "channel": "PIGEON", "keywordIndexes": [0] }]""")

            assertResponseInvalid(body)
        }

        @Test
        @DisplayName("canonicalKey 오름차순이 아닌 keywordIndexes는 USER_SERVICE_RESPONSE_INVALID다")
        fun rejectUnorderedIndexes() = runBlocking {
            val body = pageJson(
                keywords = """[{ "canonicalKey": "nvidia", "displayName": "NVIDIA" }, { "canonicalKey": "tsmc", "displayName": "TSMC" }]""",
                recipients = """[{ "userId": "user-1", "channel": "SLACK", "keywordIndexes": [1, 0] }]""",
            )

            assertResponseInvalid(body)
        }

        private suspend fun assertResponseInvalid(body: String) {
            val exception = assertFailsWith<RecipientReaderException> {
                adapterOf(jsonExchangeFunction(body)).findSubscribers(setOf("nvidia"), null)
            }

            assertEquals(RoutingErrorCode.USER_SERVICE_RESPONSE_INVALID, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("관리자 조회")
    inner class Admins {

        @Test
        @DisplayName("사용자 조회 경로에 role=ADMIN query로 호출한다")
        fun requestPath() = runBlocking {
            val exchange = CapturingExchangeFunction(adminsJson)

            adapterOf(exchange).findAdmins()

            assertEquals(RoutingTestFixture.USERS_PATH, exchange.request.url().path)
            assertEquals("role=ADMIN", exchange.request.url().rawQuery)
        }

        @Test
        @DisplayName("사용자마다 채널을 fan-out해 Recipient로 옮긴다")
        fun flattenChannels() = runBlocking {
            val recipients = adapterOf(jsonExchangeFunction(adminsJson)).findAdmins()

            assertEquals(
                listOf(
                    Recipient("admin-1", NotificationChannel.SLACK),
                    Recipient("admin-1", NotificationChannel.TELEGRAM),
                    Recipient("admin-2", NotificationChannel.DISCORD),
                ),
                recipients,
            )
        }

        @Test
        @DisplayName("관리자가 없으면 빈 리스트다")
        fun emptyList() = runBlocking {
            val recipients = adapterOf(jsonExchangeFunction("""{ "success": true, "data": [] }""")).findAdmins()

            assertTrue(recipients.isEmpty())
        }

        @Test
        @DisplayName("모르는 채널은 USER_SERVICE_RESPONSE_INVALID다")
        fun rejectUnknownChannel() = runBlocking {
            val body = """{ "success": true, "data": [ { "userId": "admin-1", "channels": ["PIGEON"] } ] }"""

            val exception = assertFailsWith<RecipientReaderException> {
                adapterOf(jsonExchangeFunction(body)).findAdmins()
            }

            assertEquals(RoutingErrorCode.USER_SERVICE_RESPONSE_INVALID, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("실패 분류")
    inner class Failures {

        @Test
        @DisplayName("비-2xx 응답은 USER_SERVICE_REQUEST_FAILED다")
        fun rejectErrorStatus() = runBlocking {
            val exception = assertFailsWith<RecipientReaderException> {
                adapterOf(jsonExchangeFunction("""{ "success": false }""", HttpStatus.SERVICE_UNAVAILABLE)).findSubscribers(setOf("tesla"), null)
            }

            assertEquals(RoutingErrorCode.USER_SERVICE_REQUEST_FAILED, exception.errorCode)
            assertTrue(exception.message!!.contains("status=503"))
        }

        @Test
        @DisplayName("success=false는 USER_SERVICE_RESPONSE_FAILED다")
        fun rejectFailureEnvelope() = runBlocking {
            val exception = assertFailsWith<RecipientReaderException> {
                adapterOf(jsonExchangeFunction("""{ "success": false, "data": null }""")).findAdmins()
            }

            assertEquals(RoutingErrorCode.USER_SERVICE_RESPONSE_FAILED, exception.errorCode)
        }

        @Test
        @DisplayName("data가 없으면 USER_SERVICE_RESPONSE_MISSING_DATA다")
        fun rejectMissingData() = runBlocking {
            val exception = assertFailsWith<RecipientReaderException> {
                adapterOf(jsonExchangeFunction("""{ "success": true }""")).findSubscribers(setOf("tesla"), null)
            }

            assertEquals(RoutingErrorCode.USER_SERVICE_RESPONSE_MISSING_DATA, exception.errorCode)
        }

        @Test
        @DisplayName("timeout은 USER_SERVICE_REQUEST_FAILED다")
        fun rejectTimeout() = runBlocking {
            val adapter = adapterOf(
                jsonExchangeFunction(subscribersJson, delay = Duration.ofMillis(300)),
                timeout = Duration.ofMillis(50),
            )

            val exception = assertFailsWith<RecipientReaderException> { adapter.findSubscribers(setOf("tesla"), null) }

            assertEquals(RoutingErrorCode.USER_SERVICE_REQUEST_FAILED, exception.errorCode)
        }
    }

    private fun adapterOf(
        exchange: ExchangeFunction,
        timeout: Duration = Duration.ofSeconds(3),
        pageSize: Int = 100,
    ): UserServiceRecipientReaderAdapter {
        val properties = RoutingTestFixture.properties(timeout = timeout, pageSize = pageSize).userService
        return UserServiceRecipientReaderAdapter(
            webClient = WebClient.builder().baseUrl(properties.baseUrl).exchangeFunction(exchange).build(),
            settings = UserServiceRecipientReaderSettings(
                recipientsSearchPath = properties.recipientsSearchPath,
                pageSize = properties.pageSize,
                usersPath = properties.usersPath,
                timeout = properties.timeout,
            ),
        )
    }

    private fun pageJson(
        keywords: String = """[{ "canonicalKey": "nvidia", "displayName": "NVIDIA" }]""",
        recipients: String,
        nextCursor: String = "null",
    ): String {
        return """{ "success": true, "data": { "keywords": $keywords, "recipients": $recipients, "nextCursor": $nextCursor }, "error": null }"""
    }
}
