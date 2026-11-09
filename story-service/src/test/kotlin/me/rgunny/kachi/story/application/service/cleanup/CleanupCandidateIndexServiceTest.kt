package me.rgunny.kachi.story.application.service.cleanup

import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.fake.InMemoryCandidateIndexPort
import me.rgunny.kachi.story.fixture.StoryTestFixture
import me.rgunny.kachi.story.fixture.StoryTestFixture.NOW
import me.rgunny.kachi.story.fixture.StoryTestFixture.article
import me.rgunny.kachi.story.fixture.StoryTestFixture.assemblyPolicy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CleanupCandidateIndexService")
class CleanupCandidateIndexServiceTest {
    private val index = InMemoryCandidateIndexPort()

    @Test
    @DisplayName("후보 검색 창을 지나 수집된 기사 벡터만 지운다")
    fun deleteVectorsBeyondCandidateWindow() = runBlocking {
        index.upsert(
            listOf(
                IndexedArticle.from(article(newsId = OLD_NEWS_ID, collectedAt = NOW.minus(Duration.ofHours(73)))),
                IndexedArticle.from(article(newsId = RECENT_NEWS_ID, collectedAt = NOW.minus(Duration.ofHours(1))))
            )
        )
        val service = CleanupCandidateIndexService(
            candidateIndexPort = index,
            assemblyPolicy = assemblyPolicy(),
            clock = StoryTestFixture.CLOCK
        )

        val result = service.cleanup()

        assertEquals(NOW.minus(Duration.ofHours(72)), result.threshold)
        assertEquals(setOf(RECENT_NEWS_ID), index.points.keys)
    }

    private companion object {
        val OLD_NEWS_ID: NewsId = NewsId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000c1"))
        val RECENT_NEWS_ID: NewsId = NewsId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000c2"))
    }
}
