package me.rgunny.kachi.ai.adapter.outbound.persistence.quarantine

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import me.rgunny.kachi.ai.adapter.outbound.persistence.outbox.AiOutboxMongoDocument
import me.rgunny.kachi.ai.application.port.outbound.quarantine.StoryQuarantinePersistencePort
import me.rgunny.kachi.ai.domain.outbox.AiOutbox
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantine
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.domain.story.StoryId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * story 격리 출력 포트의 MongoDB 구현.
 *
 * 전량 조회에 페이징이 없다.
 */
@Component
class StoryQuarantinePersistenceAdapter(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val transactionalOperator: TransactionalOperator
) : StoryQuarantinePersistencePort {

    override suspend fun findByStoryId(storyId: StoryId): StoryQuarantine? {
        return mongoTemplate.findOne(
            Query(Criteria.where("storyId").`is`(storyId.value)),
            StoryQuarantineMongoDocument::class.java
        ).awaitSingleOrNull()?.toDomain()
    }

    override suspend fun findAll(status: StoryQuarantineStatus?): List<StoryQuarantine> {
        val query = status
            ?.let { Query(Criteria.where("status").`is`(it.name)) }
            ?: Query()

        return mongoTemplate.find(query, StoryQuarantineMongoDocument::class.java)
            .collectList()
            .awaitSingle()
            .map { it.toDomain() }
    }

    override suspend fun save(quarantine: StoryQuarantine): StoryQuarantine {
        return mongoTemplate.save(StoryQuarantineMongoDocument.fromDomain(quarantine))
            .awaitSingle()
            .toDomain()
    }

    /**
     * 격리 기록을 save로, [outbox]를 insert로 한 트랜잭션에 쓴다.
     *
     * 반환값은 넘어온 [quarantine]과 같은 값이다.
     */
    override suspend fun saveQuarantined(
        quarantine: StoryQuarantine,
        outbox: AiOutbox
    ): StoryQuarantine {
        transactionalOperator.executeAndAwait {
            mongoTemplate.save(StoryQuarantineMongoDocument.fromDomain(quarantine)).awaitSingle()
            mongoTemplate.insert(AiOutboxMongoDocument.fromDomain(outbox)).awaitSingle()
        }

        return quarantine
    }
}
