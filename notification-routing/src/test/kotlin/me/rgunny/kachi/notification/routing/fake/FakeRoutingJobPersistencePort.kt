package me.rgunny.kachi.notification.routing.fake

import me.rgunny.kachi.notification.routing.application.port.outbound.job.RoutingJobPersistencePort
import me.rgunny.kachi.notification.routing.domain.RoutingJob
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobConflictException
import me.rgunny.kachi.notification.routing.exception.routing.RoutingJobTransitionConflictException

/**
 * eventKey unique와 전이 저장 조건을 흉내 내는 in-memory routing job 저장소.
 *
 * conflictOnce가 켜져 있으면 다음 insert 한 번을 충돌로 처리하면서 conflictJob을 몰래 넣어
 * "다른 인스턴스가 먼저 만든" 상황을 만든다.
 * saveTransition은 저장된 job의 id, status, cursor가 previous와 같을 때만 반영하고 saved에 남긴다.
 */
class FakeRoutingJobPersistencePort : RoutingJobPersistencePort {
    val jobsByEventKey = mutableMapOf<String, RoutingJob>()
    val inserted = mutableListOf<RoutingJob>()
    val saved = mutableListOf<RoutingJob>()
    var conflictOnce: RoutingJob? = null

    fun put(job: RoutingJob) {
        jobsByEventKey[job.eventKey] = job
    }

    override suspend fun insert(job: RoutingJob): RoutingJob {
        conflictOnce?.let { existing ->
            conflictOnce = null
            put(existing)
            throw RoutingJobConflictException(job.eventKey)
        }
        if (jobsByEventKey.containsKey(job.eventKey)) {
            throw RoutingJobConflictException(job.eventKey)
        }
        inserted += job
        put(job)
        return job
    }

    override suspend fun findByEventKey(eventKey: String): RoutingJob? {
        return jobsByEventKey[eventKey]
    }

    override suspend fun saveTransition(previous: RoutingJob, next: RoutingJob): RoutingJob {
        require(previous.id == next.id) { "transition must keep job id. previous=${previous.id}, next=${next.id}" }
        val stored = jobsByEventKey[previous.eventKey]
        if (stored == null || stored.id != previous.id || stored.status != previous.status || stored.cursor != previous.cursor) {
            throw RoutingJobTransitionConflictException(previous.eventKey, previous.cursor)
        }
        saved += next
        put(next)
        return next
    }
}
