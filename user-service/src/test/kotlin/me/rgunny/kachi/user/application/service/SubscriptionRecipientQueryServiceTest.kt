package me.rgunny.kachi.user.application.service

import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscriptionRecipientsQuery
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientPageResult
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientResult
import me.rgunny.kachi.user.application.service.fake.FakeChannelBindingPersistencePort
import me.rgunny.kachi.user.application.service.fake.FakeKeywordPersistencePort
import me.rgunny.kachi.user.application.service.fake.FakeSubscriptionPersistencePort
import me.rgunny.kachi.user.domain.ChannelBinding
import me.rgunny.kachi.user.domain.Keyword
import me.rgunny.kachi.user.domain.Subscription
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.fixture.UserTestFixture
import me.rgunny.kachi.user.fixture.UserTestFixture.activeBinding
import me.rgunny.kachi.user.fixture.UserTestFixture.keyword
import me.rgunny.kachi.user.fixture.UserTestFixture.subscription
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("SubscriptionRecipientQueryService")
class SubscriptionRecipientQueryServiceTest {
    private val nvidia: Keyword = keyword("NVIDIA")
    private val tsmc: Keyword = keyword("TSMC")

    @Nested
    @DisplayName("수신자 항목")
    inner class Recipients {

        @Test
        @DisplayName("키워드마다 채널이 다르면 채널별 항목에 그 채널의 키워드만 담는다")
        fun groupKeywordsByChannel() {
            val userId = user(1)
            val service = service(
                subscriptions = listOf(
                    subscription(userId, nvidia.id, setOf(SubscriptionChannel.SLACK)),
                    subscription(userId, tsmc.id, setOf(SubscriptionChannel.DISCORD))
                ),
                bindings = listOf(
                    activeBinding(userId, SubscriptionChannel.SLACK),
                    activeBinding(userId, SubscriptionChannel.DISCORD)
                )
            )

            val page = service.findRecipients(query(listOf("tsmc", "nvidia")))

            assertEquals(listOf("nvidia", "tsmc"), page.keywords.map { it.canonicalKey })
            assertEquals(listOf("NVIDIA", "TSMC"), page.keywords.map { it.displayName })
            assertEquals(
                listOf(
                    SubscriptionRecipientResult(userId, SubscriptionChannel.SLACK, listOf(0)),
                    SubscriptionRecipientResult(userId, SubscriptionChannel.DISCORD, listOf(1))
                ),
                page.recipients
            )
        }

        @Test
        @DisplayName("한 채널로 두 키워드를 구독하면 항목 하나에 키워드 위치 둘을 오름차순으로 담는다")
        fun mergeKeywordsOfSameChannel() {
            val userId = user(1)
            val service = service(
                subscriptions = listOf(
                    subscription(userId, tsmc.id, setOf(SubscriptionChannel.SLACK)),
                    subscription(userId, nvidia.id, setOf(SubscriptionChannel.SLACK))
                ),
                bindings = listOf(activeBinding(userId, SubscriptionChannel.SLACK))
            )

            val page = service.findRecipients(query(listOf("tsmc", "nvidia")))

            assertEquals(listOf(SubscriptionRecipientResult(userId, SubscriptionChannel.SLACK, listOf(0, 1))), page.recipients)
        }

        @Test
        @DisplayName("ACTIVE 바인딩이 없는 채널은 항목을 만들지 않는다")
        fun excludeChannelWithoutActiveBinding() {
            val userId = user(1)
            val channels = setOf(SubscriptionChannel.SLACK, SubscriptionChannel.DISCORD, SubscriptionChannel.TELEGRAM)
            val service = service(
                subscriptions = listOf(subscription(userId, nvidia.id, channels)),
                bindings = listOf(
                    activeBinding(userId, SubscriptionChannel.SLACK),
                    activeBinding(userId, SubscriptionChannel.TELEGRAM).revoke(UserTestFixture.NOW)
                )
            )

            val page = service.findRecipients(query(listOf("nvidia")))

            assertEquals(listOf(SubscriptionChannel.SLACK), page.recipients.map { it.channel })
        }

        @Test
        @DisplayName("disabled 구독의 키워드는 담지 않는다")
        fun excludeDisabledSubscription() {
            val userId = user(1)
            val service = service(
                subscriptions = listOf(
                    subscription(userId, nvidia.id),
                    subscription(userId, tsmc.id).disable(UserTestFixture.NOW)
                ),
                bindings = listOf(activeBinding(userId, SubscriptionChannel.SLACK))
            )

            val page = service.findRecipients(query(listOf("nvidia", "tsmc")))

            assertEquals(listOf(listOf(0)), page.recipients.map { it.keywordIndexes })
        }
    }

    @Nested
    @DisplayName("요청 키워드")
    inner class Keywords {

        @Test
        @DisplayName("원문 표기가 달라도 정규화해 키워드 하나로 센다")
        fun normalizeAndDeduplicate() {
            val userId = user(1)
            val service = service(
                subscriptions = listOf(subscription(userId, nvidia.id)),
                bindings = listOf(activeBinding(userId, SubscriptionChannel.SLACK))
            )

            val page = service.findRecipients(query(listOf(" NVIDIA ", "nvidia", "없는키워드")))

            assertEquals(listOf("nvidia"), page.keywords.map { it.canonicalKey })
            assertEquals(listOf(listOf(0)), page.recipients.map { it.keywordIndexes })
        }

        @Test
        @DisplayName("없는 키워드만 주면 빈 페이지다")
        fun emptyPageForUnknownKeywords() {
            val userId = user(1)
            val service = service(
                subscriptions = listOf(subscription(userId, nvidia.id)),
                bindings = listOf(activeBinding(userId, SubscriptionChannel.SLACK))
            )

            val page = service.findRecipients(query(listOf("tesla", "apple")))

            assertEquals(SubscriptionRecipientPageResult.empty(), page)
        }

        @Test
        @DisplayName("정규화하면 빈 값이 되는 키워드는 거부한다")
        fun rejectKeywordNormalizedToEmpty() {
            val service = service(subscriptions = emptyList(), bindings = emptyList())

            assertFailsWith<IllegalArgumentException> {
                service.findRecipients(query(listOf("nvidia", "!!!")))
            }
        }
    }

    @Nested
    @DisplayName("페이지")
    inner class Page {

        @Test
        @DisplayName("두 키워드를 구독한 사용자는 페이지에 한 번만 센다")
        fun countUserOnce() {
            val first = user(1)
            val second = user(2)
            val service = service(
                subscriptions = listOf(
                    subscription(first, nvidia.id),
                    subscription(first, tsmc.id),
                    subscription(second, nvidia.id)
                ),
                bindings = listOf(
                    activeBinding(first, SubscriptionChannel.SLACK),
                    activeBinding(second, SubscriptionChannel.SLACK)
                )
            )

            val page = service.findRecipients(query(listOf("nvidia", "tsmc"), limit = 2))

            assertEquals(listOf(first, second), page.recipients.map { it.userId })
            assertNull(page.nextCursor)
        }

        @Test
        @DisplayName("limit 다음 사용자가 있으면 nextCursor를 내고 두 번째 페이지는 커서 다음부터다")
        fun pageBoundary() {
            val users = (1..3).map { user(it) }
            val service = service(
                subscriptions = users.map { subscription(it, nvidia.id) },
                bindings = users.map { activeBinding(it, SubscriptionChannel.SLACK) }
            )

            val firstPage = service.findRecipients(query(listOf("nvidia"), limit = 2))
            val secondPage = service.findRecipients(query(listOf("nvidia"), cursor = firstPage.nextCursor, limit = 2))

            assertEquals(users.take(2), firstPage.recipients.map { it.userId })
            assertEquals(users[1], firstPage.nextCursor)
            assertEquals(listOf(users[2]), secondPage.recipients.map { it.userId })
            assertNull(secondPage.nextCursor)
        }

        @Test
        @DisplayName("남은 사용자가 정확히 limit명이면 nextCursor는 null이다")
        fun noNextCursorAtExactLimit() {
            val users = (1..2).map { user(it) }
            val service = service(
                subscriptions = users.map { subscription(it, nvidia.id) },
                bindings = users.map { activeBinding(it, SubscriptionChannel.SLACK) }
            )

            val page = service.findRecipients(query(listOf("nvidia"), limit = 2))

            assertEquals(users, page.recipients.map { it.userId })
            assertNull(page.nextCursor)
        }

        @Test
        @DisplayName("수신자가 0명이어도 다음 사용자가 있으면 nextCursor를 낸다")
        fun nextCursorWithoutRecipients() {
            val first = user(1)
            val second = user(2)
            val service = service(
                subscriptions = listOf(subscription(first, nvidia.id), subscription(second, nvidia.id)),
                bindings = listOf(activeBinding(second, SubscriptionChannel.SLACK))
            )

            val page = service.findRecipients(query(listOf("nvidia"), limit = 1))

            assertTrue(page.recipients.isEmpty())
            assertEquals(first, page.nextCursor)
        }

        @Test
        @DisplayName("최상위 비트가 다른 사용자를 부호 없는 바이트 순으로 병합하고 페이지를 나눈다")
        fun mergeInUnsignedByteOrder() {
            val zero = UserId.of(UUID.fromString("00000000-0000-7000-8000-000000000000"))
            val signedMax = UserId.of(UUID.fromString("7fffffff-ffff-7fff-bfff-ffffffffffff"))
            val signedMin = UserId.of(UUID.fromString("80000000-0000-7000-8000-000000000000"))
            val allOnes = UserId.of(UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"))
            val service = service(
                subscriptions = listOf(
                    subscription(signedMin, nvidia.id),
                    subscription(zero, nvidia.id),
                    subscription(allOnes, tsmc.id),
                    subscription(signedMax, tsmc.id)
                ),
                bindings = listOf(zero, signedMax, signedMin, allOnes).map { activeBinding(it, SubscriptionChannel.SLACK) }
            )

            val firstPage = service.findRecipients(query(listOf("nvidia", "tsmc"), limit = 2))
            val secondPage = service.findRecipients(query(listOf("nvidia", "tsmc"), cursor = firstPage.nextCursor, limit = 2))

            assertEquals(listOf(zero, signedMax), firstPage.recipients.map { it.userId })
            assertEquals(signedMax, firstPage.nextCursor)
            assertEquals(listOf(signedMin, allOnes), secondPage.recipients.map { it.userId })
            assertEquals(listOf(listOf(0), listOf(1)), secondPage.recipients.map { it.keywordIndexes })
            assertNull(secondPage.nextCursor)
        }
    }

    private fun service(subscriptions: List<Subscription>, bindings: List<ChannelBinding>): SubscriptionRecipientQueryService {
        return SubscriptionRecipientQueryService(
            keywordPersistencePort = FakeKeywordPersistencePort(listOf(nvidia, tsmc)),
            subscriptionPersistencePort = FakeSubscriptionPersistencePort(subscriptions),
            channelBindingPersistencePort = FakeChannelBindingPersistencePort(bindings)
        )
    }

    private fun query(keywords: List<String>, cursor: UserId? = null, limit: Int = 100): FindSubscriptionRecipientsQuery {
        return FindSubscriptionRecipientsQuery(keywords = keywords, cursor = cursor, limit = limit)
    }

    /** 순번 순서가 곧 바이트 순서인 userId. */
    private fun user(sequence: Int): UserId {
        return UserId.of(UUID.fromString("%08x-0000-7000-8000-000000000000".format(sequence)))
    }
}
