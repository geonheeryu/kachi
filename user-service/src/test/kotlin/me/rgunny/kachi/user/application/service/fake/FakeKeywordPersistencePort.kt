package me.rgunny.kachi.user.application.service.fake

import me.rgunny.kachi.user.application.port.outbound.keyword.KeywordPersistencePort
import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.Keyword
import me.rgunny.kachi.user.domain.KeywordId

/**
 * 메모리 canonical 키워드 저장소.
 *
 * 서비스 테스트에서 DB 없이 find-or-create 분기를 보기 위한 것이다.
 * [savedKeywords]로 새 키워드가 만들어졌는지 확인한다.
 * `findAllWithEnabledSubscription`은 구독 테이블을 조인하지 않고 [keywordIdsWithEnabledSubscription]으로 결과를 정한다.
 * 조인 자체는 통합 테스트가 검증한다.
 */
class FakeKeywordPersistencePort(
    keywords: List<Keyword> = emptyList(),
    private val keywordIdsWithEnabledSubscription: Set<KeywordId> = emptySet()
) : KeywordPersistencePort {
    private val keywords = keywords.associateBy { it.id }.toMutableMap()
    val savedKeywords = mutableListOf<Keyword>()

    override fun findById(keywordId: KeywordId): Keyword? = keywords[keywordId]

    override fun findAllByIds(keywordIds: Set<KeywordId>): List<Keyword> = keywordIds.mapNotNull { keywords[it] }

    override fun findByCanonicalKey(canonicalKey: CanonicalKey): Keyword? =
        keywords.values.firstOrNull { it.canonicalKey == canonicalKey }

    override fun findAllByCanonicalKeys(canonicalKeys: Set<CanonicalKey>): List<Keyword> =
        keywords.values.filter { it.canonicalKey in canonicalKeys }

    override fun findAllWithEnabledSubscription(): List<Keyword> =
        keywords.values.filter { it.id in keywordIdsWithEnabledSubscription }

    override fun save(keyword: Keyword): Keyword {
        savedKeywords += keyword
        keywords[keyword.id] = keyword
        return keyword
    }
}
