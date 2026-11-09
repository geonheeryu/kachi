package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.qdrant.client.ConditionFactory.matchKeyword
import io.qdrant.client.ConditionFactory.range
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.QdrantClient
import io.qdrant.client.QueryFactory.nearest
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.WithPayloadSelectorFactory.include
import io.qdrant.client.grpc.Common.Filter
import io.qdrant.client.grpc.Common.PointId
import io.qdrant.client.grpc.Common.Range
import io.qdrant.client.grpc.Points.DeletePoints
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.PointsIdsList
import io.qdrant.client.grpc.Points.PointsSelector
import io.qdrant.client.grpc.Points.QueryPoints
import io.qdrant.client.grpc.Points.SetPayloadPoints
import io.qdrant.client.grpc.Points.UpsertPoints
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import me.rgunny.kachi.story.application.exception.CandidateIndexException
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateHit
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.CandidateQuery
import me.rgunny.kachi.story.application.port.outbound.candidateindex.model.IndexedArticle
import me.rgunny.kachi.story.domain.NewsId
import me.rgunny.kachi.story.domain.StoryId

/**
 * Qdrant 컬렉션으로 [CandidateIndexPort]를 구현하는 adapter.
 *
 * 쓰기는 전부 `wait = true`다.
 * 점의 id는 newsId이고 같은 newsId는 덮어쓴다.
 */
class QdrantCandidateIndexAdapter(
    private val client: QdrantClient,
    private val collection: QdrantCollection
) : CandidateIndexPort {

    override suspend fun upsert(articles: List<IndexedArticle>) {
        articles.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
            val request = UpsertPoints.newBuilder()
                .setCollectionName(collection.name)
                .setWait(true)
                .addAllPoints(batch.map { it.toPoint() })
                .build()

            call { client.upsertAsync(request).await() }
        }
    }

    override suspend fun search(query: CandidateQuery): List<CandidateHit> {
        val filter = Filter.newBuilder()
            .addMust(range(QdrantPayload.COLLECTED_AT, Range.newBuilder().setGte(query.collectedAfter.toEpochMilli().toDouble()).build()))
        query.language?.let { filter.addMust(matchKeyword(QdrantPayload.LANGUAGE, it.value)) }
        val request = QueryPoints.newBuilder()
            .setCollectionName(collection.name)
            .setQuery(nearest(*query.embedding.values))
            .setFilter(filter)
            .setLimit(query.limit.toLong())
            .setWithPayload(include(listOf(QdrantPayload.STORY_ID)))
            .build()

        return call { client.queryAsync(request).await() }.map { point ->
            CandidateHit(
                newsId = NewsId.of(UUID.fromString(point.id.uuid)),
                storyId = QdrantPayload.storyIdOf(point.payloadMap),
                similarity = point.score.toDouble().coerceIn(-1.0, 1.0)
            )
        }
    }

    override suspend fun deleteByStory(storyId: StoryId) {
        delete(selectorOf(storyFilter(storyId)))
    }

    override suspend fun reassignStory(from: StoryId, to: StoryId) {
        val request = SetPayloadPoints.newBuilder()
            .setCollectionName(collection.name)
            .setWait(true)
            .setPointsSelector(selectorOf(storyFilter(from)))
            .putPayload(QdrantPayload.STORY_ID, value(to.value.toString()))
            .build()

        call { client.setPayloadAsync(request, null).await() }
    }

    override suspend fun deleteByNewsIds(newsIds: List<NewsId>) {
        if (newsIds.isEmpty()) {
            return
        }
        val ids = PointsIdsList.newBuilder().addAllIds(newsIds.map { id(it.value) }).build()

        delete(PointsSelector.newBuilder().setPoints(ids).build())
    }

    override suspend fun deleteCollectedBefore(threshold: Instant) {
        val filter = Filter.newBuilder()
            .addMust(range(QdrantPayload.COLLECTED_AT, Range.newBuilder().setLt(threshold.toEpochMilli().toDouble()).build()))
            .build()

        delete(selectorOf(filter))
    }

    private suspend fun delete(selector: PointsSelector) {
        val request = DeletePoints.newBuilder()
            .setCollectionName(collection.name)
            .setWait(true)
            .setPoints(selector)
            .build()

        call { client.deleteAsync(request).await() }
    }

    private fun IndexedArticle.toPoint(): PointStruct {
        return PointStruct.newBuilder()
            .setId(id(newsId.value))
            .setVectors(vectors(*embedding.values))
            .putAllPayload(QdrantPayload.of(this))
            .build()
    }

    private fun storyFilter(storyId: StoryId): Filter {
        return Filter.newBuilder().addMust(matchKeyword(QdrantPayload.STORY_ID, storyId.value.toString())).build()
    }

    private fun selectorOf(filter: Filter): PointsSelector = PointsSelector.newBuilder().setFilter(filter).build()

    private suspend fun <T> call(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CandidateIndexException(QdrantFailureClassifier.classify(e), e)
        }
    }

    private companion object {
        const val UPSERT_BATCH_SIZE = 100
    }
}
