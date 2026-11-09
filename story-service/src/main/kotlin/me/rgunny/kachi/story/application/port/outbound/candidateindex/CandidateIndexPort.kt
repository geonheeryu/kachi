package me.rgunny.kachi.story.application.port.outbound.candidateindex

import java.time.Instant
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateHit
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateQuery
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.StoryId

/**
 * 후보 기사를 찾는 벡터 색인의 출력 포트.
 */
interface CandidateIndexPort {

    suspend fun upsert(articles: List<IndexedArticle>)

    suspend fun search(query: CandidateQuery): List<CandidateHit>

    suspend fun deleteByStory(storyId: StoryId)

    /**
     * [from]의 기사가 [to]를 가리키게 한다.
     */
    suspend fun reassignStory(from: StoryId, to: StoryId)

    suspend fun deleteByNewsIds(newsIds: List<NewsId>)

    suspend fun deleteCollectedBefore(threshold: Instant)
}
