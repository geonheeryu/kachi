package me.rgunny.kachi.user.adapter.outbound.persistence.keyword

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import me.rgunny.kachi.user.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.user.adapter.outbound.persistence.subscription.SubscriptionPersistenceAdapter
import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.Keyword
import me.rgunny.kachi.user.domain.KeywordName
import me.rgunny.kachi.user.domain.Subscription
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.fixture.UserTestFixture
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@DisplayName("KeywordPersistenceAdapter 통합 테스트")
class KeywordPersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var keywordPersistenceAdapter: KeywordPersistenceAdapter

    @Autowired
    private lateinit var subscriptionPersistenceAdapter: SubscriptionPersistenceAdapter

    private val now = UserTestFixture.NOW

    @Nested
    @DisplayName("save()")
    inner class Save {

        @Test
        @DisplayName("canonical 키워드를 저장하고 canonicalKey로 다시 찾는다")
        fun saveAndFindByCanonicalKey() {
            val saved = keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("SPACE-X"), now))
            flushAndClear()

            val found = keywordPersistenceAdapter.findByCanonicalKey(CanonicalKey.of("space-x"))

            assertNotNull(found)
            assertEquals(saved.id, found.id)
            assertEquals("SPACE-X", found.displayName.value)
        }

        @Test
        @DisplayName("canonicalKey 여럿 중 존재하는 키워드만 찾는다")
        fun findAllByCanonicalKeys() {
            val nvidia = keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("NVIDIA"), now))
            val tsmc = keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("TSMC"), now))
            keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("Tesla"), now))
            flushAndClear()

            val found = keywordPersistenceAdapter.findAllByCanonicalKeys(
                setOf(CanonicalKey.of("nvidia"), CanonicalKey.of("tsmc"), CanonicalKey.of("apple"))
            )

            assertEquals(setOf(nvidia.id, tsmc.id), found.map { it.id }.toSet())
        }

        @Test
        @DisplayName("같은 canonicalKey는 두 번 저장할 수 없다")
        fun rejectDuplicateCanonicalKey() {
            keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("Tesla"), now))
            keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("TESLA"), now))

            assertFailsWith<PersistenceException> {
                flushAndClear()
            }
        }

        @Test
        @DisplayName("canonicalKey 비교는 대소문자를 구분한다 — 동등성은 코드의 정규화가 정한다")
        fun canonicalKeyIsBinaryCollated() {
            keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("tesla"), now))
            flushAndClear()

            val found = entityManager
                .createNativeQuery("select count(*) from keywords where canonical_key = 'TESLA'")
                .singleResult

            assertEquals(0L, (found as Number).toLong())
        }
    }

    @Nested
    @DisplayName("findAllWithEnabledSubscription()")
    inner class FindAllWithEnabledSubscription {

        @Test
        @DisplayName("enabled 구독이 하나 이상인 키워드만 조회한다")
        fun findOnlyKeywordsWithEnabledSubscription() {
            val subscribed = keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("Tesla"), now))
            val disabledOnly = keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("NVIDIA"), now))
            keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("Bitcoin"), now))
            subscriptionPersistenceAdapter.save(subscription(subscribed))
            subscriptionPersistenceAdapter.save(subscription(disabledOnly).disable(now))
            flushAndClear()

            val keywords = keywordPersistenceAdapter.findAllWithEnabledSubscription()

            assertEquals(listOf(subscribed.id), keywords.map { it.id })
        }

        @Test
        @DisplayName("구독이 없으면 빈 목록이다")
        fun emptyWhenNoSubscription() {
            keywordPersistenceAdapter.save(Keyword.create(KeywordName.of("Bitcoin"), now))
            flushAndClear()

            assertEquals(emptyList(), keywordPersistenceAdapter.findAllWithEnabledSubscription())
            assertNull(keywordPersistenceAdapter.findByCanonicalKey(CanonicalKey.of("nothing")))
        }
    }

    private fun subscription(keyword: Keyword): Subscription {
        return Subscription.create(
            userId = UserId.newId(),
            keywordId = keyword.id,
            channels = setOf(SubscriptionChannel.SLACK),
            registeredAt = now
        )
    }
}
