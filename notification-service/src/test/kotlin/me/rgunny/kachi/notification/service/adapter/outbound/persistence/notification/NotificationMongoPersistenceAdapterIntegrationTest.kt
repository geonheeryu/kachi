package me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.notification.domain.Notification
import me.rgunny.kachi.notification.domain.NotificationChannel
import me.rgunny.kachi.notification.domain.NotificationOrigin
import me.rgunny.kachi.notification.domain.NotificationStatus
import me.rgunny.kachi.notification.service.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification.NotificationDocument
import me.rgunny.kachi.notification.service.adapter.outbound.persistence.notification.NotificationHistoryDocument
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

@DisplayName("NotificationMongoPersistenceAdapter 통합 테스트")
class NotificationMongoPersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var adapter: NotificationMongoPersistenceAdapter

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    private val requestedAt = Instant.parse("2026-06-16T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        mongoTemplate.remove(Query(), NotificationDocument::class.java).block()
        mongoTemplate.remove(Query(), NotificationHistoryDocument::class.java).block()
    }

    @Nested
    @DisplayName("save/find")
    inner class SaveFind {

        @Test
        @DisplayName("Notification aggregate를 저장하고 id/requestId로 조회한다")
        fun saveAndFind() = runBlocking {
            val notification = notification()

            val saved = adapter.save(notification)

            assertEquals(notification.id, saved.id)
            assertNotNull(adapter.findById(notification.id))
            assertEquals(notification.id, adapter.findByRequestId("request-1")?.id)
        }

        @Test
        @DisplayName("story 출처의 storyId·version·keywords를 저장하고 복원한다")
        fun saveAndFindStoryOrigin() = runBlocking {
            val origin = NotificationOrigin(
                summaryId = "summary-1",
                storyId = "story-1",
                version = 2,
                keywords = listOf("tesla", "nvidia"),
                userId = "user-1",
            )
            val notification = notification(origin = origin)

            adapter.save(notification)

            assertEquals(origin, adapter.findById(notification.id)?.origin)
        }
    }

    @Nested
    @DisplayName("claimFromPublished()")
    inner class ClaimFromPublished {

        @Test
        @DisplayName("PUBLISHED 상태만 PROCESSING으로 원자 claim한다")
        fun claimPublished() = runBlocking {
            val publishedAt = Instant.parse("2026-06-16T00:00:10Z")
            val claimedAt = Instant.parse("2026-06-16T00:00:20Z")
            val notification = notification()
                .markPublished(publishedAt)
            adapter.save(notification)

            val claimed = adapter.claimFromPublished(notification.id, "worker-1", claimedAt)
            val secondClaim = adapter.claimFromPublished(notification.id, "worker-2", claimedAt)

            assertNotNull(claimed)
            assertEquals(NotificationStatus.PROCESSING, claimed.status)
            assertEquals(claimedAt, claimed.updatedAt)
            assertEquals(claimedAt, claimed.lastTransitionAt)
            assertEquals(claimedAt, claimed.claimedAt)
            assertEquals("worker-1", claimed.claimedBy)
            val histories = findHistories(notification)
            assertEquals(2, histories.size)
            assertEquals(NotificationStatus.PUBLISHED.name, histories.last().fromStatus)
            assertEquals(NotificationStatus.PROCESSING.name, histories.last().toStatus)
            assertNull(secondClaim)
        }
    }

    @Nested
    @DisplayName("claimFromRetryWait()")
    inner class ClaimFromRetryWait {

        @Test
        @DisplayName("RETRY_WAIT 상태만 PROCESSING으로 원자 claim한다")
        fun claimRetryWait() = runBlocking {
            val notification = notification()
                .markPublished(Instant.parse("2026-06-16T00:00:10Z"))
                .markProcessing(Instant.parse("2026-06-16T00:00:20Z"), "worker-1")
                .markFailed(Instant.parse("2026-06-16T00:00:30Z"), "temporary failure")
                .markRetryWait(Instant.parse("2026-06-16T00:00:40Z"), "temporary failure")
            val claimedAt = Instant.parse("2026-06-16T00:00:50Z")
            adapter.save(notification)

            val claimed = adapter.claimFromRetryWait(notification.id, "worker-1", claimedAt)

            assertNotNull(claimed)
            assertEquals(NotificationStatus.PROCESSING, claimed.status)
            assertEquals(claimedAt, claimed.updatedAt)
            assertEquals(claimedAt, claimed.claimedAt)
            assertEquals("worker-1", claimed.claimedBy)
            val histories = findHistories(notification)
            assertEquals(5, histories.size)
            assertEquals(NotificationStatus.RETRY_WAIT.name, histories.last().fromStatus)
            assertEquals(NotificationStatus.PROCESSING.name, histories.last().toStatus)
        }
    }

    private fun findHistories(notification: Notification): List<NotificationHistoryDocument> {
        val query = Query.query(Criteria.where("notificationId").`is`(notification.id.id.toString()))
            .with(Sort.by(Sort.Order.asc("createdAt")))

        return mongoTemplate.find(query, NotificationHistoryDocument::class.java)
            .collectList()
            .block() ?: emptyList()
    }

    private fun notification(origin: NotificationOrigin = NotificationOrigin.NONE): Notification {
        return Notification.request(
            requestId = "request-1",
            requester = "collector-service",
            channel = NotificationChannel.SLACK,
            recipientId = "user-1",
            message = "hello",
            origin = origin,
            now = requestedAt,
        )
    }
}
