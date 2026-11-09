package me.rgunny.kachi.e2e.support

import org.testcontainers.Testcontainers
import org.testcontainers.lifecycle.Startables
import java.security.SecureRandom
import java.util.Base64

/**
 * e2e 전체가 공유하는 실행 환경: 인프라 다섯 개, 테스트 JVM 안의 외부 vendor stub 넷, 서비스 컨테이너 일곱 개.
 *
 * stub은 Google News RSS, LLM, TEI 임베딩 서버, TEI judge 서버다. 발송 vendor는 worker의 mock sender다.
 * Slack·Discord 주소는 user-service가 실제 vendor 접두사를 강제해 stub URL을 바인딩에 넣을 수 없다(ADR 029).
 * 서비스는 local 프로파일로 뜨고, 환경변수로 덮는 것은 인프라·stub 주소, 주기 작업의 대기 시간, 시나리오가 정하는 격리 임계치,
 * 시나리오 밖에서 stub을 부르는 주기 작업의 스위치다.
 *
 * user-service가 seed 데이터를 심고 뜬 뒤 나머지 여섯이 함께 뜬다.
 * JVM 하나에 한 번 뜬다. [shared]의 첫 호출이 띄우고 JVM 종료 hook이 내린다.
 */
class KachiCluster private constructor() : AutoCloseable {
    val infrastructure = KachiInfrastructure()
    val news = TestGoogleNewsServer()
    val llm = TestLlmServer()
    val teiEmbedding = TestTeiServer(TestTeiRole.EMBEDDING)
    val teiReranker = TestTeiServer(TestTeiRole.RERANKER)
    val jwtSecret: String = generateSecret()

    lateinit var userService: KachiServiceContainer
        private set
    lateinit var collector: KachiServiceContainer
        private set
    lateinit var storyService: KachiServiceContainer
        private set
    lateinit var aiService: KachiServiceContainer
        private set
    lateinit var routing: KachiServiceContainer
        private set
    lateinit var notificationService: KachiServiceContainer
        private set
    lateinit var worker: KachiServiceContainer
        private set

    /** 만든 서비스 컨테이너. 기동 도중 실패해도 여기 있는 것만 내린다. */
    private val services = mutableListOf<KachiServiceContainer>()

    /** 각 서비스 컨테이너 로그의 마지막 [lines]줄을 서비스 이름과 함께 이어 붙인다. */
    fun serviceLogTails(lines: Int): String {
        return services.joinToString("\n\n") { "===== ${it.module} =====\n${it.logTail(lines)}" }
    }

    override fun close() {
        services.asReversed().forEach { it.stop() }
        services.clear()
        listOf(news, llm, teiEmbedding, teiReranker).forEach { it.close() }
        infrastructure.close()
    }

    private fun start() {
        infrastructure.start()
        Testcontainers.exposeHostPorts(news.port, llm.port, teiEmbedding.port, teiReranker.port)

        userService = service(
            module = "user-service",
            port = 8080,
            environment = commonEnvironment(database = "kachi_user") + mapOf(
                "SPRING_DATASOURCE_URL" to infrastructure.mysqlJdbcUrlInNetwork,
                "SPRING_DATASOURCE_USERNAME" to KachiInfrastructure.MYSQL_USER,
                "SPRING_DATASOURCE_PASSWORD" to KachiInfrastructure.MYSQL_PASSWORD,
                "SPRING_JPA_SHOW_SQL" to "false",
                "KACHI_JWT_SECRET" to jwtSecret,
                "KACHI_USER_TELEGRAM_BOT_USERNAME" to TELEGRAM_BOT_USERNAME
            )
        )
        userService.start()

        val userServiceUrl = userService.urlInNetwork
        collector = service(
            module = "collector-service",
            port = 8082,
            environment = commonEnvironment(database = "kachi_collector") + mapOf(
                "KACHI_USER_SERVICE_BASE_URL" to userServiceUrl,
                // 켜 둔 provider는 Google 하나(credential이 없는 provider)
                "KACHI_COLLECTOR_PROVIDERS_GOOGLE_BASEURL" to hostUrl(news.port),
                "KACHI_COLLECTOR_PROVIDERS_NAVER_ENABLED" to "false",
                "KACHI_COLLECTOR_PROVIDERS_FINNHUB_ENABLED" to "false",
                // 수집은 테스트가 internal API로 일으킨다(스케줄러 실행과의 409 경합 방지)
                "KACHI_COLLECTOR_SCHEDULER_NEWS_ENABLED" to "false",
                "KACHI_COLLECTOR_OUTBOX_RELAY_INITIALDELAY" to RELAY_DELAY,
                "KACHI_COLLECTOR_OUTBOX_RELAY_FIXEDDELAY" to RELAY_DELAY
            )
        )
        storyService = service(
            module = "story-service",
            port = 8087,
            environment = commonEnvironment(database = "kachi_story") + mapOf(
                "KACHI_STORY_TEI_EMBEDDING_BASE_URL" to hostUrl(teiEmbedding.port),
                "KACHI_STORY_TEI_RERANKER_BASE_URL" to hostUrl(teiReranker.port),
                "KACHI_STORY_QDRANT_HOST" to KachiInfrastructure.QDRANT_ALIAS,
                "KACHI_STORY_QDRANT_GRPC_PORT" to KachiInfrastructure.QDRANT_GRPC_PORT.toString(),
                "KACHI_STORY_OUTBOX_RELAY_INITIALDELAY" to RELAY_DELAY,
                "KACHI_STORY_OUTBOX_RELAY_FIXEDDELAY" to RELAY_DELAY
            )
        )
        aiService = service(
            module = "ai-service",
            port = 8083,
            environment = commonEnvironment(database = "kachi_ai") + mapOf(
                "KACHI_USER_SERVICE_BASE_URL" to userServiceUrl,
                // local 프로파일의 LLM 후보는 Ollama 하나다. 인증이 없어 키도 없다.
                "KACHI_AI_LLM_PROVIDERS_OLLAMA_BASEURL" to hostUrl(llm.port),
                "KACHI_AI_OUTBOX_RELAY_INITIALDELAY" to RELAY_DELAY,
                "KACHI_AI_OUTBOX_RELAY_FIXEDDELAY" to RELAY_DELAY,
                "KACHI_AI_STORYQUARANTINE_FAILURETHRESHOLD" to QUARANTINE_FAILURE_THRESHOLD.toString(),
                // 시나리오 밖에서 LLM stub을 부르는 키워드 확장 tick
                "KACHI_AI_SCHEDULER_KEYWORDEXPANSION_ENABLED" to "false"
            )
        )
        routing = service(
            module = "notification-routing",
            port = 8086,
            environment = commonEnvironment(database = "kachi_notification_routing") + mapOf(
                "KACHI_USER_SERVICE_BASE_URL" to userServiceUrl
            )
        )
        notificationService = service(
            module = "notification-service",
            port = 8084,
            environment = commonEnvironment(database = NOTIFICATION_DATABASE)
        )
        worker = service(
            module = "notification-worker",
            port = 8085,
            environment = commonEnvironment(database = NOTIFICATION_DATABASE) + mapOf(
                "KACHI_USER_SERVICE_BASE_URL" to userServiceUrl,
                // 실 sender를 전부 끄면 mock sender가 모든 채널을 맡는다.
                "KACHI_NOTIFICATION_SENDER_SLACK_ENABLED" to "false",
                "KACHI_NOTIFICATION_SENDER_DISCORD_ENABLED" to "false",
                "KACHI_NOTIFICATION_SENDER_TELEGRAM_ENABLED" to "false",
                "KACHI_NOTIFICATION_TELEGRAM_BOT_TOKEN" to "e2e-unused"
            )
        )
        Startables.deepStart(listOf(collector, storyService, aiService, routing, notificationService, worker)).join()
    }

    private fun service(module: String, port: Int, environment: Map<String, String>): KachiServiceContainer {
        return KachiServiceContainer(module, port, infrastructure.network, environment).also { services += it }
    }

    private fun commonEnvironment(database: String): Map<String, String> {
        return mapOf(
            "SPRING_KAFKA_BOOTSTRAP_SERVERS" to infrastructure.kafkaBootstrapInNetwork,
            "SPRING_DATA_REDIS_HOST" to KachiInfrastructure.REDIS_ALIAS,
            "SPRING_DATA_REDIS_PORT" to KachiInfrastructure.REDIS_PORT.toString(),
            "SPRING_MONGODB_URI" to infrastructure.mongoUriInNetwork(database)
        )
    }

    private fun hostUrl(port: Int): String = "http://host.testcontainers.internal:$port"

    private fun generateSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        const val NOTIFICATION_DATABASE = "kachi_notification"
        const val TELEGRAM_BOT_USERNAME = "kachi_e2e_bot"
        const val QUARANTINE_FAILURE_THRESHOLD = 2
        // outbox relay의 initial-delay·fixed-delay(기본 15s·5s)
        private const val RELAY_DELAY = "1s"

        private val instance: Result<KachiCluster> by lazy {
            runCatching {
                val cluster = KachiCluster()
                try {
                    cluster.start()
                } catch (error: Throwable) {
                    cluster.close()
                    throw error
                }
                Runtime.getRuntime().addShutdownHook(Thread(cluster::close))
                cluster
            }
        }

        /**
         * JVM 하나가 공유하는 클러스터.
         *
         * 첫 호출이 띄운다. 기동에 실패했으면 이후 호출도 같은 예외로 실패한다.
         */
        fun shared(): KachiCluster = instance.getOrThrow()
    }
}
