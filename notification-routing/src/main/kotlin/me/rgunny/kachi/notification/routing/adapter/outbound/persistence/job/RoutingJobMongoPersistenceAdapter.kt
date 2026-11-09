package me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job.RoutingJobDocument
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job.RoutingJobDocumentMapper
import me.rgunny.kachi.notification.routing.application.port.outbound.job.RoutingJobPersistencePort
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobConflictException
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobTransitionConflictException
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

/**
 * routing job MongoDB 구현.
 *
 * insert는 eventKey unique 위반을 RoutingJobConflictException으로 바꿔 돌려준다.
 * saveTransition은 `_id + status + cursor` 조건의 findAndModify이고, 맞는 문서가 없으면 RoutingJobTransitionConflictException을 던진다.
 */
@Repository
class RoutingJobMongoPersistenceAdapter(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val mapper: RoutingJobDocumentMapper,
) : RoutingJobPersistencePort {

    override suspend fun insert(job: RoutingJob): RoutingJob {
        return try {
            mongoTemplate.insert(mapper.toDocument(job))
                .map(mapper::toDomain)
                .awaitSingle()
        } catch (exception: DuplicateKeyException) {
            throw RoutingJobConflictException(job.eventKey, exception)
        }
    }

    override suspend fun findByEventKey(eventKey: String): RoutingJob? {
        return mongoTemplate.findOne(
            Query.query(Criteria.where(FIELD_EVENT_KEY).`is`(eventKey)),
            RoutingJobDocument::class.java,
        )
            .map(mapper::toDomain)
            .awaitSingleOrNull()
    }

    override suspend fun saveTransition(previous: RoutingJob, next: RoutingJob): RoutingJob {
        require(previous.id == next.id) { "transition must keep job id. previous=${previous.id}, next=${next.id}" }

        return mongoTemplate.findAndModify(
            transitionQuery(previous),
            transitionUpdate(next),
            FindAndModifyOptions.options().returnNew(true),
            RoutingJobDocument::class.java,
        )
            .map(mapper::toDomain)
            .awaitSingleOrNull()
            ?: throw RoutingJobTransitionConflictException(previous.eventKey, previous.cursor)
    }

    private fun transitionQuery(previous: RoutingJob): Query {
        return Query.query(
            Criteria.where(FIELD_ID).`is`(previous.id.id.toString())
                .and(FIELD_STATUS).`is`(previous.status.name)
                .and(FIELD_CURSOR).`is`(previous.cursor)
        )
    }

    private fun transitionUpdate(next: RoutingJob): Update {
        val document = mapper.toDocument(next)

        return Update()
            .set(FIELD_STATUS, document.status)
            .set(FIELD_CURSOR, document.cursor)
            .set(FIELD_TARGET_COUNT, document.targetCount)
            .set(FIELD_PUBLISHED_COUNT, document.publishedCount)
            .set(FIELD_UPDATED_AT, document.updatedAt)
            .set(FIELD_COMPLETED_AT, document.completedAt)
    }

    private companion object {
        const val FIELD_ID = "_id"
        const val FIELD_EVENT_KEY = "eventKey"
        const val FIELD_STATUS = "status"
        const val FIELD_CURSOR = "cursor"
        const val FIELD_TARGET_COUNT = "targetCount"
        const val FIELD_PUBLISHED_COUNT = "publishedCount"
        const val FIELD_UPDATED_AT = "updatedAt"
        const val FIELD_COMPLETED_AT = "completedAt"
    }
}
