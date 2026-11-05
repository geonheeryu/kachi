package me.rgunny.kachi.user.config

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.user.adapter.outbound.persistence.binding.ChannelBindingJpaEntity
import me.rgunny.kachi.user.adapter.outbound.persistence.binding.ChannelBindingJpaRepository
import me.rgunny.kachi.user.adapter.outbound.persistence.keyword.KeywordJpaEntity
import me.rgunny.kachi.user.adapter.outbound.persistence.keyword.KeywordJpaRepository
import me.rgunny.kachi.user.adapter.outbound.persistence.subscription.SubscriptionJpaEntity
import me.rgunny.kachi.user.adapter.outbound.persistence.subscription.SubscriptionJpaRepository
import me.rgunny.kachi.user.adapter.outbound.persistence.user.UserJpaEntity
import me.rgunny.kachi.user.adapter.outbound.persistence.user.UserJpaRepository
import me.rgunny.kachi.user.application.port.outbound.binding.AddressCipherPort
import me.rgunny.kachi.user.domain.AuthProvider
import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.ChannelAddress
import me.rgunny.kachi.user.domain.ChannelBinding
import me.rgunny.kachi.user.domain.Email
import me.rgunny.kachi.user.domain.Keyword
import me.rgunny.kachi.user.domain.KeywordName
import me.rgunny.kachi.user.domain.Nickname
import me.rgunny.kachi.user.domain.Subscription
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.User
import me.rgunny.kachi.user.domain.UserId
import me.rgunny.kachi.user.domain.UserRole
import me.rgunny.kachi.user.domain.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 로컬 개발용 시드 데이터.
 *
 * `local` 프로파일에서만 기동 시 한 번 실행되어 시드 사용자와 SLACK 바인딩, 키워드 구독, 그리고 세 채널 바인딩을 가진 관리자 사용자를 심는다.
 * 사용자 등록·로그인·구독을 손으로 하지 않아도 활성 키워드 API가 채워져 수집·요약을 바로 돌릴 수 있고,
 * 역할별 수신자 API가 관리자를 돌려주어 격리 알림 라우팅을 바로 확인할 수 있다.
 * 관리자는 시드 키워드를 세 채널로 구독한다. 관리자만 실주소 바인딩 설정이 있으므로, 스모크에서 요약 알림을 실제 채널로 받는 수신자다.
 * 모든 단계가 find-or-create라 재기동해도 중복이 생기지 않는다.
 * 시드 사용자의 바인딩 주소는 형식만 맞춘 값이다. 관리자의 바인딩 주소는 [UserSeedProperties]에 설정된 값을 쓰고,
 * 없는 채널만 형식만 맞춘 값으로 심는다. 이미 있는 관리자 바인딩의 주소가 설정값과 다르면 설정값으로 바꾼다.
 *
 * 유스케이스가 아니라 JPA repository를 직접 쓴다. 유스케이스를 거치면 활성 사용자·채널 바인딩 검증을 시드가 만족시켜야 하고,
 * repository를 직접 만지는 코드는 레이어 규칙상 config에만 둘 수 있다.
 */
@Configuration
@Profile("local")
@EnableConfigurationProperties(UserSeedProperties::class)
class LocalSeedDataConfig {

    /** 시드 사용자 -> SLACK 바인딩 -> canonical 키워드 -> 구독 -> 관리자 사용자 -> 관리자 바인딩 -> 관리자 구독 순으로 심고 결과를 한 줄 남긴다. */
    @Bean
    fun localSeedDataInitializer(
        userJpaRepository: UserJpaRepository,
        channelBindingJpaRepository: ChannelBindingJpaRepository,
        keywordJpaRepository: KeywordJpaRepository,
        subscriptionJpaRepository: SubscriptionJpaRepository,
        addressCipherPort: AddressCipherPort,
        clock: Clock,
        seedProperties: UserSeedProperties
    ): ApplicationRunner {
        return ApplicationRunner {
            val now = Instant.now(clock)
            val seedUser = findOrCreateSeedUser(userJpaRepository, now)
            findOrCreateBinding(
                channelBindingJpaRepository,
                addressCipherPort,
                seedUser,
                placeholderAddress(SubscriptionChannel.SLACK),
                now
            )
            val keywords = SEED_KEYWORD_NAMES.map { findOrCreateKeyword(keywordJpaRepository, it, now) }
            val subscriptions = keywords.map { findOrCreateSubscription(subscriptionJpaRepository, seedUser, it, SEED_CHANNELS, now) }
            val adminUser = findOrCreateAdminUser(userJpaRepository, now)
            SubscriptionChannel.entries.forEach { channel ->
                findOrCreateBinding(channelBindingJpaRepository, addressCipherPort, adminUser, adminAddress(seedProperties, channel), now)
            }
            val adminSubscriptions = keywords.map {
                findOrCreateSubscription(subscriptionJpaRepository, adminUser, it, SubscriptionChannel.entries.toSet(), now)
            }

            log.info(
                "Local seed data initialized userId={} subscriptionCount={} keywords={} adminUserId={} adminSubscriptionCount={}",
                seedUser.id,
                subscriptions.size,
                keywords.map { it.canonicalKey.value },
                adminUser.id,
                adminSubscriptions.size
            )
        }
    }

    /** 시드 사용자를 이메일로 찾고 없으면 LOCAL provider 사용자로 등록한다. */
    private fun findOrCreateSeedUser(userJpaRepository: UserJpaRepository, registeredAt: Instant): User {
        val existingUser = userJpaRepository.findByEmail(SEED_USER_EMAIL)
        if (existingUser != null) {
            return existingUser.toDomain()
        }

        val seedUser = User.register(
            email = Email.of(SEED_USER_EMAIL),
            nickname = Nickname.of(SEED_USER_NICKNAME),
            authProvider = AuthProvider.LOCAL,
            providerUserId = null,
            registeredAt = registeredAt
        )

        return userJpaRepository.save(UserJpaEntity.from(seedUser)).toDomain()
    }

    /**
     * 관리자 사용자를 이메일로 찾고 없으면 ADMIN 역할로 만든다.
     * 가입 경로는 역할을 USER로 고정하므로 `restore`로 직접 조립한다.
     */
    private fun findOrCreateAdminUser(userJpaRepository: UserJpaRepository, registeredAt: Instant): User {
        val existingUser = userJpaRepository.findByEmail(SEED_ADMIN_EMAIL)
        if (existingUser != null) {
            return existingUser.toDomain()
        }

        val adminUser = User.restore(
            id = UserId.newId(),
            email = Email.of(SEED_ADMIN_EMAIL),
            nickname = Nickname.of(SEED_ADMIN_NICKNAME),
            status = UserStatus.ACTIVE,
            role = UserRole.ADMIN,
            authProvider = AuthProvider.LOCAL,
            providerUserId = null,
            registeredAt = registeredAt,
            lastLoginAt = null,
            deactivatedAt = null
        )

        return userJpaRepository.save(UserJpaEntity.from(adminUser)).toDomain()
    }

    /** 설정된 관리자 주소가 있으면 그것, 없으면 자리표시 주소. 형식이 틀리면 기동 시점에 바로 실패한다. */
    private fun adminAddress(seedProperties: UserSeedProperties, channel: SubscriptionChannel): ChannelAddress {
        val configured = seedProperties.adminAddresses.of(channel) ?: return placeholderAddress(channel)
        return ChannelAddress.of(channel, configured)
    }

    private fun placeholderAddress(channel: SubscriptionChannel): ChannelAddress {
        return ChannelAddress.of(channel, PLACEHOLDER_ADDRESSES.getValue(channel))
    }

    /**
     * 사용자의 채널 바인딩을 찾고, 없으면 만들고, 해지돼 있거나 주소가 다르면 이 주소로 ACTIVE가 되게 한다.
     * 활성이고 주소도 같으면 저장하지 않는다.
     */
    private fun findOrCreateBinding(
        channelBindingJpaRepository: ChannelBindingJpaRepository,
        addressCipherPort: AddressCipherPort,
        user: User,
        address: ChannelAddress,
        createdAt: Instant
    ): ChannelBinding {
        val existing = channelBindingJpaRepository.findByUserIdAndChannel(user.id.value, address.channel)
        if (existing != null) {
            val binding = existing.toDomain(addressCipherPort)
            return if (binding.isActive && binding.address == address) {
                binding
            } else {
                channelBindingJpaRepository.save(ChannelBindingJpaEntity.from(binding.bindAddress(address, createdAt), addressCipherPort))
                    .toDomain(addressCipherPort)
            }
        }

        val binding = ChannelBinding.createWithAddress(userId = user.id, address = address, createdAt = createdAt)

        return channelBindingJpaRepository.save(ChannelBindingJpaEntity.from(binding, addressCipherPort)).toDomain(addressCipherPort)
    }

    /** 이름을 정규화해 canonical 키워드를 찾고 없으면 원문을 displayName으로 만든다. */
    private fun findOrCreateKeyword(
        keywordJpaRepository: KeywordJpaRepository,
        name: String,
        createdAt: Instant
    ): Keyword {
        val canonicalKey = CanonicalKey.of(name)
        val existing = keywordJpaRepository.findByCanonicalKey(canonicalKey.value)
        if (existing != null) {
            return existing.toDomain()
        }

        val keyword = Keyword.create(displayName = KeywordName.of(name), createdAt = createdAt)

        return keywordJpaRepository.save(KeywordJpaEntity.from(keyword)).toDomain()
    }

    /** 사용자의 구독을 찾고 없으면 [channels]로 만든다. 비활성화돼 있으면 다시 활성화해 수집 대상에 올린다. */
    private fun findOrCreateSubscription(
        subscriptionJpaRepository: SubscriptionJpaRepository,
        user: User,
        keyword: Keyword,
        channels: Set<SubscriptionChannel>,
        registeredAt: Instant
    ): Subscription {
        val existing = subscriptionJpaRepository.findByUserIdAndKeywordId(user.id.value, keyword.id.value)
        if (existing != null) {
            val subscription = existing.toDomain()
            return if (subscription.enabled) {
                subscription
            } else {
                subscriptionJpaRepository.save(SubscriptionJpaEntity.from(subscription.enable())).toDomain()
            }
        }

        val subscription = Subscription.create(
            userId = user.id,
            keywordId = keyword.id,
            channels = channels,
            registeredAt = registeredAt
        )

        return subscriptionJpaRepository.save(SubscriptionJpaEntity.from(subscription)).toDomain()
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalSeedDataConfig::class.java)

        private const val SEED_USER_EMAIL = "collector-admin@kachi.local"
        private const val SEED_USER_NICKNAME = "collector-admin"
        private val SEED_KEYWORD_NAMES = listOf("TRUMP", "NVIDIA", "SPACE-X", "TESLA", "이란")
        private val SEED_CHANNELS = setOf(SubscriptionChannel.SLACK)
        private const val SEED_ADMIN_EMAIL = "admin@kachi.local"
        private const val SEED_ADMIN_NICKNAME = "admin"
        private val PLACEHOLDER_ADDRESSES = mapOf(
            SubscriptionChannel.SLACK to "https://hooks.slack.com/services/LOCAL/SEED/WEBHOOK",
            SubscriptionChannel.DISCORD to "https://discord.com/api/webhooks/000000/LOCAL-SEED",
            SubscriptionChannel.TELEGRAM to "000000000"
        )
    }
}
