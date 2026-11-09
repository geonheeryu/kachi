package me.rgunny.kachi.user.adapter.outbound.persistence.keyword

import me.rgunny.kachi.user.application.port.outbound.keyword.KeywordPersistencePort
import me.rgunny.kachi.user.domain.CanonicalKey
import me.rgunny.kachi.user.domain.Keyword
import me.rgunny.kachi.user.domain.KeywordId
import org.springframework.stereotype.Repository

/**
 * canonical 키워드 저장소. 도메인과 JPA 엔티티 사이 변환만 담당한다.
 */
@Repository
class KeywordPersistenceAdapter(
    private val keywordJpaRepository: KeywordJpaRepository
) : KeywordPersistencePort {

    override fun findById(keywordId: KeywordId): Keyword? {
        return keywordJpaRepository.findById(keywordId.value)
            .map { it.toDomain() }
            .orElse(null)
    }

    override fun findAllByIds(keywordIds: Set<KeywordId>): List<Keyword> {
        if (keywordIds.isEmpty()) {
            return emptyList()
        }

        return keywordJpaRepository.findAllById(keywordIds.map { it.value })
            .map { it.toDomain() }
    }

    override fun findByCanonicalKey(canonicalKey: CanonicalKey): Keyword? {
        return keywordJpaRepository.findByCanonicalKey(canonicalKey.value)?.toDomain()
    }

    override fun findAllByCanonicalKeys(canonicalKeys: Set<CanonicalKey>): List<Keyword> {
        if (canonicalKeys.isEmpty()) {
            return emptyList()
        }

        return keywordJpaRepository.findAllByCanonicalKeyIn(canonicalKeys.map { it.value })
            .map { it.toDomain() }
    }

    override fun findAllWithEnabledSubscription(): List<Keyword> {
        return keywordJpaRepository.findAllWithEnabledSubscription()
            .map { it.toDomain() }
    }

    override fun save(keyword: Keyword): Keyword {
        return keywordJpaRepository.save(KeywordJpaEntity.from(keyword)).toDomain()
    }
}
