package me.rgunny.kachi.e2e.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Random
import kotlin.math.sqrt

/**
 * story-service가 부르는 TEI 추론 서버 하나를 흉내 내는 stub.
 *
 * 두 역할 모두 `GET /health`, `GET /info`, `POST /tokenize`에 답한다.
 * [TestTeiRole.EMBEDDING]은 `POST /embed`에, [TestTeiRole.RERANKER]는 `POST /rerank`에 답한다.
 * `/info`는 TEI 1.9.3이 BAAI/bge-m3·BAAI/bge-reranker-v2-m3를 띄웠을 때의 응답이다.
 *
 * 임베딩은 입력 텍스트의 [StoryMarker]에서 결정적으로 만든다.
 * 같은 표지의 두 기사는 코사인 약 0.96, 표지가 다르거나 없는 기사와는 0 근처다.
 * rerank 점수는 질의와 후보의 표지가 같으면 [SAME_STORY_SCORE], 다르면 [OTHER_STORY_SCORE]다.
 */
class TestTeiServer(private val role: TestTeiRole) : AutoCloseable {
    private val server = TestStubServer()
    private val jsonMapper = JsonMapper.builder().build()

    val port: Int get() = server.port

    init {
        server.respond(HEALTH_PATH, TestStubResponse(statusCode = 200, body = ""))
        server.respond(INFO_PATH, TestStubResponse(statusCode = 200, body = infoOf(role)))
        server.route(TOKENIZE_PATH) { request -> json(tokenize(jsonMapper.readTree(request.body))) }
        when (role) {
            TestTeiRole.EMBEDDING -> server.route(EMBED_PATH) { request -> json(embed(jsonMapper.readTree(request.body))) }
            TestTeiRole.RERANKER -> server.route(RERANK_PATH) { request -> json(rerank(jsonMapper.readTree(request.body))) }
        }
    }

    override fun close() {
        server.close()
    }

    private fun embed(request: JsonNode): List<FloatArray> {
        return items(request.path("inputs")).map { vectorOf(it.asText()) }
    }

    private fun rerank(request: JsonNode): List<Map<String, Any>> {
        val queryMarker = StoryMarker.findIn(request.path("query").asText())
        return items(request.path("texts"))
            .mapIndexed { index, text ->
                val sameStory = queryMarker != null && StoryMarker.findIn(text.asText()) == queryMarker
                mapOf("index" to index, "score" to if (sameStory) SAME_STORY_SCORE else OTHER_STORY_SCORE)
            }
            .sortedByDescending { it.getValue("score") as Double }
    }

    private fun tokenize(request: JsonNode): List<List<Map<String, Any>>> {
        return items(request.path("inputs")).map { input ->
            val words = input.asText().split(WHITESPACE).filter { it.isNotEmpty() }
            val tokens = words.mapIndexed { index, word -> mapOf("id" to index + 1L, "text" to word, "special" to false) }
            listOf(BOS) + tokens + listOf(EOS)
        }
    }

    private fun items(array: JsonNode): List<JsonNode> = (0 until array.size()).map { array.get(it) }

    private fun json(value: Any): TestStubResponse {
        return TestStubResponse(statusCode = 200, body = jsonMapper.writeValueAsString(value))
    }

    companion object {
        const val EMBEDDING_DIMENSION = 1024
        const val SAME_STORY_SCORE = 0.95
        const val OTHER_STORY_SCORE = 0.05

        private const val HEALTH_PATH = "/health"
        private const val INFO_PATH = "/info"
        private const val EMBED_PATH = "/embed"
        private const val RERANK_PATH = "/rerank"
        private const val TOKENIZE_PATH = "/tokenize"
        // 기사마다 다른 성분의 크기(같은 story 코사인 1/(1+0.2^2) 근처)
        private const val ARTICLE_NOISE_WEIGHT = 0.2
        private val WHITESPACE = Regex("\\s+")
        private val BOS = mapOf("id" to 0L, "text" to "<s>", "special" to true)
        private val EOS = mapOf("id" to 2L, "text" to "</s>", "special" to true)

        /**
         * 텍스트의 stub 임베딩. 길이 1로 정규화한 [EMBEDDING_DIMENSION]차원 벡터다.
         *
         * story 성분은 표지(없으면 텍스트 전체), 기사 성분은 텍스트 전체에서 정한다.
         */
        fun vectorOf(text: String): FloatArray {
            val storyKey = StoryMarker.findIn(text)?.value ?: text
            val story = unitGaussian(storyKey)
            val article = unitGaussian(text)
            val combined = DoubleArray(EMBEDDING_DIMENSION) { story[it] + ARTICLE_NOISE_WEIGHT * article[it] }
            val norm = sqrt(combined.sumOf { it * it })
            return FloatArray(EMBEDDING_DIMENSION) { (combined[it] / norm).toFloat() }
        }

        private fun unitGaussian(key: String): DoubleArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
            val seed = digest.take(Long.SIZE_BYTES).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
            val random = Random(seed)
            val values = DoubleArray(EMBEDDING_DIMENSION) { random.nextGaussian() }
            val norm = sqrt(values.sumOf { it * it })
            return DoubleArray(EMBEDDING_DIMENSION) { values[it] / norm }
        }

        private fun infoOf(role: TestTeiRole): String {
            return when (role) {
                TestTeiRole.EMBEDDING -> EMBEDDING_INFO
                TestTeiRole.RERANKER -> RERANKER_INFO
            }
        }

        private val EMBEDDING_INFO = """
            {
              "model_id": "BAAI/bge-m3",
              "model_sha": "5617a9f61b028005a4858fdac845db406aefb181",
              "model_dtype": "float32",
              "served_model_name": "BAAI/bge-m3",
              "model_type": {"embedding": {"pooling": "cls"}},
              "max_concurrent_requests": 32,
              "max_input_length": 512,
              "max_batch_tokens": 512,
              "max_batch_requests": 8,
              "max_client_batch_size": 32,
              "auto_truncate": true,
              "tokenization_workers": 9,
              "version": "1.9.3"
            }
        """.trimIndent()

        private val RERANKER_INFO = """
            {
              "model_id": "BAAI/bge-reranker-v2-m3",
              "model_sha": "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e",
              "model_dtype": "float16",
              "served_model_name": "BAAI/bge-reranker-v2-m3",
              "model_type": {"reranker": {"id2label": {"0": "LABEL_0"}, "label2id": {"LABEL_0": 0}}},
              "max_concurrent_requests": 32,
              "max_input_length": 512,
              "max_batch_tokens": 512,
              "max_batch_requests": 4,
              "max_client_batch_size": 32,
              "auto_truncate": true,
              "tokenization_workers": 9,
              "version": "1.9.3"
            }
        """.trimIndent()
    }
}
