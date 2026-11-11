package me.rgunny.kachi.user.application.port.outbound.keyword

import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.Keyword
import me.rgunny.kachi.user.domain.KeywordId

/**
 * canonical 키워드 저장소 출력 포트.
 *
 * canonicalKey로 찾기(find-or-create의 find)와 enabled 구독이 걸린 키워드 조회(활성 키워드 API)를 제공한다.
 */
interface KeywordPersistencePort {
    fun findById(keywordId: KeywordId): Keyword?

    fun findAllByIds(keywordIds: Set<KeywordId>): List<Keyword>

    fun findByCanonicalKey(canonicalKey: CanonicalKey): Keyword?

    /** [canonicalKeys] 중 존재하는 키워드. 순서는 정하지 않는다. */
    fun findAllByCanonicalKeys(canonicalKeys: Set<CanonicalKey>): List<Keyword>

    /** enabled 구독이 하나 이상 있는 키워드 */
    fun findAllWithEnabledSubscription(): List<Keyword>

    fun save(keyword: Keyword): Keyword
}
