package me.rgunny.kachi.story.application.service.index

import java.time.Clock
import java.time.Instant
import me.rgunny.kachi.story.application.port.inbound.index.RebuildCandidateIndexUseCase
import me.rgunny.kachi.story.application.port.inbound.index.model.RebuildCandidateIndexResult
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.application.port.outbound.story.StoryArticlePersistencePort
import me.rgunny.kachi.story.application.port.outbound.story.StoryPersistencePort
import me.rgunny.kachi.story.application.service.assembly.AssemblyPolicy
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.StoryArticle
import me.rgunny.kachi.story.domain.StoryStatus
import org.springframework.stereotype.Service

/**
 * 저장된 임베딩으로 후보 색인을 다시 만드는 유스케이스.
 *
 * 색인을 비운 뒤 보관 창([AssemblyPolicy.candidateWindow]) 안의 기사를 페이징으로 읽어 OPEN story의 기사만 다시 넣는다.
 */
@Service
class RebuildCandidateIndexService(
    private val storyArticlePersistencePort: StoryArticlePersistencePort,
    private val storyPersistencePort: StoryPersistencePort,
    private val candidateIndexPort: CandidateIndexPort,
    private val assemblyPolicy: AssemblyPolicy,
    private val clock: Clock
) : RebuildCandidateIndexUseCase {

    override suspend fun rebuild(): RebuildCandidateIndexResult {
        val now = Instant.now(clock)
        candidateIndexPort.deleteCollectedBefore(now)

        val threshold = now.minus(assemblyPolicy.candidateWindow)
        var scannedCount = 0
        var indexedCount = 0
        var after: NewsId? = null
        while (true) {
            val page = storyArticlePersistencePort.findCollectedAfter(threshold, after, PAGE_SIZE)
            if (page.isEmpty()) {
                break
            }
            scannedCount += page.size
            indexedCount += indexOpenStoryArticles(page)
            after = page.last().newsId
            if (page.size < PAGE_SIZE) {
                break
            }
        }

        return RebuildCandidateIndexResult(
            scannedCount = scannedCount,
            indexedCount = indexedCount,
            skippedClosedCount = scannedCount - indexedCount
        )
    }

    private suspend fun indexOpenStoryArticles(page: List<StoryArticle>): Int {
        val openStoryIds = storyPersistencePort.findByIds(page.map { it.storyId }.toSet())
            .filter { it.status == StoryStatus.OPEN }
            .map { it.id }
            .toSet()
        val indexable = page.filter { it.storyId in openStoryIds }
        if (indexable.isNotEmpty()) {
            candidateIndexPort.upsert(indexable.map(IndexedArticle::from))
        }

        return indexable.size
    }

    companion object {
        internal const val PAGE_SIZE = 500
    }
}
