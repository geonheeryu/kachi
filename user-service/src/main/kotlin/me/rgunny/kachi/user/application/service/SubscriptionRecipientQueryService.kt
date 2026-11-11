package me.rgunny.kachi.user.application.service

import me.rgunny.kachi.user.application.port.inbound.internal.FindSubscriptionRecipientsUseCase
import me.rgunny.kachi.user.application.port.inbound.internal.model.FindSubscriptionRecipientsQuery
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientKeywordResult
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientPageResult
import me.rgunny.kachi.user.application.port.inbound.internal.model.SubscriptionRecipientResult
import me.rgunny.kachi.user.application.port.outbound.binding.ChannelBindingPersistencePort
import me.rgunny.kachi.user.application.port.outbound.keyword.KeywordPersistencePort
import me.rgunny.kachi.user.application.port.outbound.subscription.SubscriptionPersistencePort
import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.KeywordId
import me.rgunny.kachi.user.domain.Subscription
import me.rgunny.kachi.user.domain.SubscriptionChannel
import me.rgunny.kachi.user.domain.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 여러 키워드의 수신자를 사용자 단위 keyset 페이지로 내는 조회 서비스.
 *
 * 페이지의 사용자는 키워드별 keyset 조회 결과를 userId 바이트 순으로 병합해 앞에서부터 고른다.
 * enabled 구독의 채널 중 그 사용자의 ACTIVE 바인딩이 있는 채널만 수신자로 센다.
 */
@Service
@Transactional(readOnly = true)
class SubscriptionRecipientQueryService(
    private val keywordPersistencePort: KeywordPersistencePort,
    private val subscriptionPersistencePort: SubscriptionPersistencePort,
    private val channelBindingPersistencePort: ChannelBindingPersistencePort
) : FindSubscriptionRecipientsUseCase {

    override fun findRecipients(query: FindSubscriptionRecipientsQuery): SubscriptionRecipientPageResult {
        // 1. 요청 키워드를 정규화하고 존재하는 키워드만 canonicalKey 순으로 남긴다.
        val canonicalKeys = query.keywords.map(CanonicalKey::of).toSet()
        val keywords = keywordPersistencePort.findAllByCanonicalKeys(canonicalKeys)
            .sortedBy { it.canonicalKey.value }
        if (keywords.isEmpty()) return SubscriptionRecipientPageResult.empty()

        // 2. 키워드마다 커서 다음 사용자를 limit + 1명씩 읽어 병합하고 앞의 limit명을 고른다.
        val candidates = keywords
            .flatMap { subscriptionPersistencePort.findEnabledUserIdsByKeywordId(it.id, query.cursor, query.limit + 1) }
            .distinct()
            .sortedWith(UserIdByteOrder)
        val pageUserIds = candidates.take(query.limit)
        val nextCursor = if (candidates.size > query.limit) pageUserIds.last() else null

        // 3. 고른 사용자의 enabled 구독과 ACTIVE 바인딩을 각각 한 번에 읽는다.
        val keywordIndexes = keywords.withIndex().associate { (index, keyword) -> keyword.id to index }
        val subscriptions = subscriptionPersistencePort.findAllEnabledByKeywordIdsAndUserIds(
            keywordIds = keywordIndexes.keys,
            userIds = pageUserIds.toSet()
        )
        val activeChannels = channelBindingPersistencePort.findAllActiveByUserIds(pageUserIds.toSet())
            .map { it.userId to it.channel }
            .toSet()

        // 4. (userId, channel)마다 그 채널로 구독한 키워드의 위치를 모은다.
        return SubscriptionRecipientPageResult(
            keywords = keywords.map(SubscriptionRecipientKeywordResult::from),
            recipients = recipients(pageUserIds, subscriptions, keywordIndexes, activeChannels),
            nextCursor = nextCursor
        )
    }

    /**
     * [pageUserIds] 순서, 그 안에서 채널 선언 순서로 낸다.
     *
     * ACTIVE 바인딩이 없는 채널은 항목을 만들지 않는다.
     */
    private fun recipients(
        pageUserIds: List<UserId>,
        subscriptions: List<Subscription>,
        keywordIndexes: Map<KeywordId, Int>,
        activeChannels: Set<Pair<UserId, SubscriptionChannel>>
    ): List<SubscriptionRecipientResult> {
        val indexesByRecipient = subscriptions
            .flatMap { subscription ->
                subscription.channels.map { channel ->
                    (subscription.userId to channel) to keywordIndexes.getValue(subscription.keywordId)
                }
            }
            .filter { (recipient, _) -> recipient in activeChannels }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        return pageUserIds.flatMap { userId ->
            SubscriptionChannel.entries.mapNotNull { channel ->
                indexesByRecipient[userId to channel]?.let { indexes ->
                    SubscriptionRecipientResult(userId = userId, channel = channel, keywordIndexes = indexes.sorted())
                }
            }
        }
    }
}
