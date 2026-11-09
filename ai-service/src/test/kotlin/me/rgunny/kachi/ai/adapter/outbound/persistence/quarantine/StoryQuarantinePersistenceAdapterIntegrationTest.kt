package me.rgunny.kachi.ai.adapter.outbound.persistence.quarantine

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.ai.adapter.outbound.persistence.PersistenceAdapterIntegrationTest
import me.rgunny.kachi.ai.domain.quarantine.StoryQuarantineStatus
import me.rgunny.kachi.ai.fixture.AiTestFixture
import me.rgunny.kachi.ai.support.AiOutboxCollection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query

@DisplayName("StoryQuarantinePersistenceAdapter 통합 테스트")
class StoryQuarantinePersistenceAdapterIntegrationTest : PersistenceAdapterIntegrationTest() {

    @Autowired
    private lateinit var adapter: StoryQuarantinePersistenceAdapter

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    private val outboxes: AiOutboxCollection by lazy { AiOutboxCollection(mongoTemplate) }

    @BeforeEach
    fun cleanUp() {
        mongoTemplate.remove(Query(), StoryQuarantineMongoDocument::class.java).block()
        outboxes.clear()
    }

    @Test
    @DisplayName("같은 기록의 갱신은 대체되고 storyId로 조회한다")
    fun saveAndFindByStoryId() = runBlocking {
        val tracked = AiTestFixture.storyQuarantine(consecutiveFailures = 1)
        adapter.save(tracked)
        adapter.save(tracked.recordSuccess(AiTestFixture.NOW))

        val found = adapter.findByStoryId(AiTestFixture.STORY_ID)

        assertEquals(0, found?.consecutiveFailures)
        assertNull(adapter.findByStoryId(AiTestFixture.OTHER_STORY_ID))
    }

    @Test
    @DisplayName("상태 조건으로 걸러 읽는다")
    fun findAllByStatus() = runBlocking {
        adapter.save(AiTestFixture.storyQuarantine(consecutiveFailures = 3))
        adapter.save(AiTestFixture.storyQuarantine(storyId = AiTestFixture.OTHER_STORY_ID, consecutiveFailures = 1))

        assertEquals(2, adapter.findAll(status = null).size)
        assertEquals(
            listOf(AiTestFixture.STORY_ID),
            adapter.findAll(status = StoryQuarantineStatus.QUARANTINED).map { it.storyId }
        )
    }

    @Test
    @DisplayName("격리 전이와 발행 대기 이벤트를 함께 저장한다")
    fun saveQuarantinedWithOutbox() = runBlocking {
        val quarantine = AiTestFixture.storyQuarantine(consecutiveFailures = 3)

        adapter.saveQuarantined(quarantine, AiTestFixture.outbox(eventKey = "story-quarantine-1"))

        assertEquals(StoryQuarantineStatus.QUARANTINED, adapter.findByStoryId(AiTestFixture.STORY_ID)?.status)
        assertEquals(1, outboxes.findAll().size)
    }
}
