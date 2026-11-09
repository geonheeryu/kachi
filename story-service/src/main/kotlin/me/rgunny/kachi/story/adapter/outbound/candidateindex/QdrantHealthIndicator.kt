package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.qdrant.client.QdrantClient
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator
import org.springframework.boot.health.contributor.Health
import reactor.core.publisher.Mono

/**
 * Qdrant의 health check를 actuator health에 싣는 indicator.
 *
 * 응답이 오면 up, 예외면 down이다.
 */
class QdrantHealthIndicator(
    private val client: QdrantClient
) : AbstractReactiveHealthIndicator() {

    override fun doHealthCheck(builder: Health.Builder): Mono<Health> {
        return mono {
            val reply = client.healthCheckAsync().await()

            builder.up().withDetail("title", reply.title).withDetail("version", reply.version).build()
        }
    }
}
