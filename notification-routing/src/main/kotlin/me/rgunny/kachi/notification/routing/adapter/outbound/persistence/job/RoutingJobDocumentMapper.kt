package me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job

import java.util.UUID
import me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job.RoutingJobDocument
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.domain.RoutingJobId
import me.rgunny.kachi.notification.routing.domain.RoutingJobKind
import me.rgunny.kachi.notification.routing.domain.RoutingJobStatus
import org.springframework.stereotype.Component

/**
 * RoutingJob domain <-> Mongo document 매퍼.
 */
@Component
class RoutingJobDocumentMapper {

    fun toDocument(job: RoutingJob): RoutingJobDocument {
        return RoutingJobDocument(
            id = job.id.id.toString(),
            eventKey = job.eventKey,
            kind = job.kind.name,
            keyword = job.keyword,
            status = job.status.name,
            cursor = job.cursor,
            targetCount = job.targetCount,
            publishedCount = job.publishedCount,
            createdAt = job.createdAt,
            updatedAt = job.updatedAt,
            completedAt = job.completedAt,
        )
    }

    fun toDomain(document: RoutingJobDocument): RoutingJob {
        return RoutingJob.restore(
            id = RoutingJobId.of(UUID.fromString(document.id)),
            eventKey = document.eventKey,
            kind = RoutingJobKind.valueOf(document.kind),
            keyword = document.keyword,
            status = RoutingJobStatus.valueOf(document.status),
            cursor = document.cursor,
            targetCount = document.targetCount,
            publishedCount = document.publishedCount,
            createdAt = document.createdAt,
            updatedAt = document.updatedAt,
            completedAt = document.completedAt,
        )
    }
}
