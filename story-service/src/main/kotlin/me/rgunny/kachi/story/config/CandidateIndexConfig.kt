package me.rgunny.kachi.story.config

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import kotlinx.coroutines.runBlocking
import me.rgunny.kachi.story.adapter.outbound.candidateindex.QdrantHealthIndicator
import me.rgunny.kachi.story.adapter.outbound.candidateindex.QdrantCandidateIndexAdapter
import me.rgunny.kachi.story.adapter.outbound.candidateindex.QdrantCollection
import me.rgunny.kachi.story.application.port.outbound.candidateindex.CandidateIndexPort
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 벡터 색인 호출 층을 조립하는 설정.
 *
 * 컬렉션은 임베딩 모델마다 하나이고 이름에 모델 code가 들어간다.
 */
@Configuration
@EnableConfigurationProperties(CandidateIndexProperties::class)
class CandidateIndexConfig {

    @Bean(destroyMethod = "close")
    fun qdrantClient(properties: CandidateIndexProperties): QdrantClient {
        val grpcClient = QdrantGrpcClient.newBuilder(properties.host, properties.grpcPort, properties.tls)
            .withTimeout(properties.timeout)
            .build()

        return QdrantClient(grpcClient)
    }

    @Bean
    fun qdrantCollection(
        client: QdrantClient,
        properties: CandidateIndexProperties,
        inferenceProperties: InferenceProperties
    ): QdrantCollection {
        val model = inferenceProperties.embedding.model

        return QdrantCollection(
            client = client,
            name = properties.collectionName(model.code),
            dimension = model.dimension
        )
    }

    @Bean
    fun candidateIndexPort(client: QdrantClient, collection: QdrantCollection): CandidateIndexPort {
        return QdrantCandidateIndexAdapter(client = client, collection = collection)
    }

    /** 기동 시 컬렉션과 payload index를 만들고 벡터 차원을 대조한다. */
    @Bean
    fun candidateIndexStartupProbe(collection: QdrantCollection): ApplicationRunner {
        return ApplicationRunner {
            runBlocking { collection.ensure() }
            log.info("Qdrant collection verified: name={}, dimension={}", collection.name, collection.dimension)
        }
    }

    @Bean("qdrant")
    fun qdrantHealthIndicator(client: QdrantClient): QdrantHealthIndicator {
        return QdrantHealthIndicator(client)
    }

    private companion object {
        val log = LoggerFactory.getLogger(CandidateIndexConfig::class.java)
    }
}
