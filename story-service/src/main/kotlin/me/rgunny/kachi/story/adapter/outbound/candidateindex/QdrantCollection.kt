package me.rgunny.kachi.story.adapter.outbound.candidateindex

import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections.Datatype
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.PayloadSchemaType
import io.qdrant.client.grpc.Collections.VectorParams
import kotlinx.coroutines.guava.await

/**
 * 기사 벡터를 담는 Qdrant 컬렉션 하나.
 *
 * 벡터는 [dimension] 차원 cosine, float32다.
 */
class QdrantCollection(
    private val client: QdrantClient,
    val name: String,
    val dimension: Int
) {
    init {
        require(name.isNotBlank()) { "컬렉션 이름은 빈 값일 수 없습니다" }
        require(dimension >= 1) { "벡터 차원은 1 이상이어야 합니다: $dimension" }
    }

    /**
     * 컬렉션과 payload index를 만든다.
     * 이미 있으면 벡터 차원이 [dimension]과 같은지 확인하고, 다르면 [IllegalStateException]을 던진다.
     */
    suspend fun ensure() {
        if (client.collectionExistsAsync(name).await()) {
            val actual = client.getCollectionInfoAsync(name).await().config.params.vectorsConfig.params.size
            check(actual == dimension.toLong()) {
                "Qdrant 컬렉션 $name 의 벡터 차원이 다릅니다: expected=$dimension, actual=$actual"
            }
        } else {
            client.createCollectionAsync(
                name,
                VectorParams.newBuilder()
                    .setSize(dimension.toLong())
                    .setDistance(Distance.Cosine)
                    .setDatatype(Datatype.Float32)
                    .build()
            ).await()
        }
        createIndex(QdrantPayload.STORY_ID, PayloadSchemaType.Keyword)
        createIndex(QdrantPayload.COLLECTED_AT, PayloadSchemaType.Integer)
        createIndex(QdrantPayload.LANGUAGE, PayloadSchemaType.Keyword)
    }

    private suspend fun createIndex(field: String, schema: PayloadSchemaType) {
        client.createPayloadIndexAsync(name, field, schema, null, true, null, null).await()
    }
}
