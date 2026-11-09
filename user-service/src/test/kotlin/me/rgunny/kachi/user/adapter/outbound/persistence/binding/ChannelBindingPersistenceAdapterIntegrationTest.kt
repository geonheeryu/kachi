package me.rgunny.kachi.user.adapter.outbound.persistence.binding

import jakarta.persistence.PersistenceException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import me.rgunny.kachi.user.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.user.application.port.outbound.binding.model.ActiveChannelBinding
import me.rgunny.kachi.user.domain.ChannelAddress
import me.rgunny.kachi.user.domain.ChannelBinding
import me.rgunny.kachi.user.domain.ChannelBindingStatus
import me.rgunny.kachi.user.domain.LinkToken
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.fixture.UserTestFixture
import me.rgunny.kachi.user.fixture.UserTestFixture.activeBinding
import me.rgunny.kachi.user.fixture.UserTestFixture.address
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@DisplayName("ChannelBindingPersistenceAdapter 통합 테스트")
class ChannelBindingPersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var channelBindingPersistenceAdapter: ChannelBindingPersistenceAdapter

    @Autowired
    private lateinit var channelBindingJpaRepository: ChannelBindingJpaRepository

    private val now = UserTestFixture.NOW

    @Nested
    @DisplayName("save()")
    inner class Save {

        @Test
        @DisplayName("주소는 암호문으로 저장되고 읽을 때 복호화된다")
        fun saveEncryptedAddress() {
            val saved = channelBindingPersistenceAdapter.save(activeBinding(UserId.newId(), SubscriptionChannel.SLACK))
            flushAndClear()

            val entity = channelBindingJpaRepository.findById(saved.id.value).orElseThrow()
            val stored = String(entity.addressCiphertext!!, Charsets.ISO_8859_1)
            assertFalse(stored.contains("hooks.slack.com"))
            assertEquals(1, entity.keyVersion)

            val found = channelBindingPersistenceAdapter.findById(saved.id)
            assertNotNull(found)
            assertEquals(address(SubscriptionChannel.SLACK), found.address)
            assertEquals(ChannelBindingStatus.ACTIVE, found.status)
        }

        @Test
        @DisplayName("PENDING 바인딩은 토큰 해시와 만료 시각을 저장하고 해시로 찾는다")
        fun savePendingAndFindByHash() {
            val token = LinkToken.issue(now, Duration.ofMinutes(10))
            val pending = ChannelBinding.createPending(UserId.newId(), SubscriptionChannel.TELEGRAM, token, now)
            channelBindingPersistenceAdapter.save(pending)
            flushAndClear()

            val found = channelBindingPersistenceAdapter.findByLinkTokenHash(token.hash())

            assertNotNull(found)
            assertEquals(pending.id, found.id)
            assertEquals(token.expiresAt, found.linkTokenExpiresAt)
            assertNull(found.address)
        }

        @Test
        @DisplayName("해지하면 같은 행의 주소가 비워진다")
        fun revokeClearsCiphertext() {
            val saved = channelBindingPersistenceAdapter.save(activeBinding(UserId.newId(), SubscriptionChannel.SLACK))
            flushAndClear()

            channelBindingPersistenceAdapter.save(saved.revoke(now))
            flushAndClear()

            val entity = channelBindingJpaRepository.findById(saved.id.value).orElseThrow()
            assertNull(entity.addressCiphertext)
            assertEquals(ChannelBindingStatus.REVOKED, entity.status)
        }

        @Test
        @DisplayName("같은 사용자는 같은 채널의 바인딩을 두 개 가질 수 없다")
        fun rejectDuplicateUserChannel() {
            val userId = UserId.newId()
            channelBindingPersistenceAdapter.save(activeBinding(userId, SubscriptionChannel.SLACK))
            channelBindingPersistenceAdapter.save(activeBinding(userId, SubscriptionChannel.SLACK))

            assertFailsWith<PersistenceException> { flushAndClear() }
        }
    }

    @Nested
    @DisplayName("조회")
    inner class Find {

        @Test
        @DisplayName("사용자·채널로 찾고 사용자의 목록을 조회한다")
        fun findByUserAndChannel() {
            val userId = UserId.newId()
            channelBindingPersistenceAdapter.save(activeBinding(userId, SubscriptionChannel.SLACK))
            channelBindingPersistenceAdapter.save(
                ChannelBinding.createWithAddress(
                    userId,
                    ChannelAddress.of(SubscriptionChannel.DISCORD, "https://discord.com/api/webhooks/1/a"),
                    now
                )
            )
            channelBindingPersistenceAdapter.save(activeBinding(UserId.newId(), SubscriptionChannel.SLACK))
            flushAndClear()

            val slack = channelBindingPersistenceAdapter.findByUserIdAndChannel(userId, SubscriptionChannel.SLACK)
            assertNotNull(slack)
            assertEquals(address(SubscriptionChannel.SLACK), slack.address)
            assertNull(channelBindingPersistenceAdapter.findByUserIdAndChannel(userId, SubscriptionChannel.TELEGRAM))

            assertEquals(
                setOf(SubscriptionChannel.SLACK, SubscriptionChannel.DISCORD),
                channelBindingPersistenceAdapter.findAllByUserId(userId).map { it.channel }.toSet()
            )
        }

        @Test
        @DisplayName("여러 사용자의 바인딩을 한 번에 조회하고 주소를 복호화한다")
        fun findAllByUserIds() {
            val alice = UserId.newId()
            val bob = UserId.newId()
            channelBindingPersistenceAdapter.save(activeBinding(alice, SubscriptionChannel.SLACK))
            channelBindingPersistenceAdapter.save(activeBinding(bob, SubscriptionChannel.TELEGRAM))
            channelBindingPersistenceAdapter.save(activeBinding(UserId.newId(), SubscriptionChannel.SLACK))
            flushAndClear()

            val bindings = channelBindingPersistenceAdapter.findAllByUserIds(setOf(alice, bob))

            assertEquals(setOf(alice to SubscriptionChannel.SLACK, bob to SubscriptionChannel.TELEGRAM), bindings.map { it.userId to it.channel }.toSet())
            assertEquals(address(SubscriptionChannel.TELEGRAM), bindings.single { it.userId == bob }.address)
            assertEquals(emptyList(), channelBindingPersistenceAdapter.findAllByUserIds(emptySet()))
        }

        @Test
        @DisplayName("여러 사용자의 ACTIVE 바인딩만 (userId, channel)으로 조회한다")
        fun findAllActiveByUserIds() {
            val alice = UserId.newId()
            val bob = UserId.newId()
            channelBindingPersistenceAdapter.save(activeBinding(alice, SubscriptionChannel.SLACK))
            channelBindingPersistenceAdapter.save(activeBinding(alice, SubscriptionChannel.DISCORD).revoke(now))
            channelBindingPersistenceAdapter.save(
                ChannelBinding.createPending(alice, SubscriptionChannel.TELEGRAM, LinkToken.issue(now, Duration.ofMinutes(10)), now)
            )
            channelBindingPersistenceAdapter.save(activeBinding(bob, SubscriptionChannel.TELEGRAM))
            channelBindingPersistenceAdapter.save(activeBinding(UserId.newId(), SubscriptionChannel.SLACK))
            flushAndClear()

            val bindings = channelBindingPersistenceAdapter.findAllActiveByUserIds(setOf(alice, bob))

            assertEquals(
                setOf(
                    ActiveChannelBinding(alice, SubscriptionChannel.SLACK),
                    ActiveChannelBinding(bob, SubscriptionChannel.TELEGRAM)
                ),
                bindings.toSet()
            )
            assertEquals(2, bindings.size)
        }

        @Test
        @DisplayName("빈 사용자 집합이면 빈 목록이다")
        fun findAllActiveByEmptyUserIds() {
            channelBindingPersistenceAdapter.save(activeBinding(UserId.newId(), SubscriptionChannel.SLACK))
            flushAndClear()

            assertEquals(emptyList(), channelBindingPersistenceAdapter.findAllActiveByUserIds(emptySet()))
        }
    }
}
