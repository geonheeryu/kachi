package me.rgunny.kachi.notification.routing.adapter.outbound.persistence.job

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * routing job MongoDB 저장 모델.
 * eventKey unique가 같은 이벤트의 이중 라우팅을 막는다.
 */
@Document("routing_jobs")
data class RoutingJobDocument(
    @Id
    val id: String,
    @Indexed(unique = true, name = "ux_routing_jobs_event_key")
    val eventKey: String,
    val kind: String,
    val keyword: String,
    val status: String,
    val cursor: String?,
    val targetCount: Int,
    val publishedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)
