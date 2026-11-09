package me.rgunny.kachi.e2e.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.time.Instant

/**
 * 서비스 컨테이너들이 공유하는 인프라 다섯 개(MySQL, Mongo replica set, Redis, Kafka, Qdrant)와 Docker network.
 *
 * 서비스는 network alias(`mysql`·`mongo`·`redis`·`kafka`·`qdrant`)로 붙고, 테스트 JVM은 host에 매핑된 포트로 붙는다.
 * Mongo는 alias를 멤버 host로 replica set을 직접 초기화한다. 로컬 compose(`infra/docker-compose.mongo.yml`)와 같은 모양이다.
 */
class KachiInfrastructure : AutoCloseable {
    val network: Network = Network.newNetwork()

    val mysql: MySQLContainer = MySQLContainer("mysql:8.0")
        .withDatabaseName(MYSQL_DATABASE)
        .withUsername(MYSQL_USER)
        .withPassword(MYSQL_PASSWORD)
        .withNetwork(network)
        .withNetworkAliases(MYSQL_ALIAS)

    val mongo: GenericContainer<*> = GenericContainer("mongo:7.0")
        .withCommand("mongod", "--replSet", MONGO_REPLICA_SET, "--bind_ip_all")
        .withExposedPorts(MONGO_PORT)
        .withNetwork(network)
        .withNetworkAliases(MONGO_ALIAS)
        .waitingFor(Wait.forListeningPort())

    val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
        .withExposedPorts(REDIS_PORT)
        .withNetwork(network)
        .withNetworkAliases(REDIS_ALIAS)
        .waitingFor(Wait.forListeningPort())

    val kafka: KafkaContainer = KafkaContainer("apache/kafka:3.9.1")
        .withNetwork(network)
        .withNetworkAliases(KAFKA_ALIAS)
        // 컨테이너 안의 서비스가 alias로 붙는 listener. 테스트 JVM은 bootstrapServers(host 매핑)로 붙는다.
        .withListener("$KAFKA_ALIAS:$KAFKA_INTERNAL_PORT")

    // story-service 통합 테스트와 로컬 compose의 이미지
    val qdrant: GenericContainer<*> = GenericContainer("qdrant/qdrant:v1.19.1")
        .withExposedPorts(QDRANT_HTTP_PORT, QDRANT_GRPC_PORT)
        .withNetwork(network)
        .withNetworkAliases(QDRANT_ALIAS)
        .waitingFor(Wait.forHttp("/readyz").forPort(QDRANT_HTTP_PORT).forStatusCode(200))

    fun start() {
        Startables.deepStart(mysql, mongo, redis, kafka, qdrant).join()
        initiateReplicaSet()
    }

    /** 테스트 JVM이 붙는 Mongo URI. replica set 멤버 host가 alias라 host에서는 direct connection으로 붙는다. */
    fun mongoUriFromHost(database: String): String {
        return "mongodb://${mongo.host}:${mongo.getMappedPort(MONGO_PORT)}/$database?directConnection=true"
    }

    fun mongoUriInNetwork(database: String): String {
        return "mongodb://$MONGO_ALIAS:$MONGO_PORT/$database?replicaSet=$MONGO_REPLICA_SET"
    }

    val kafkaBootstrapInNetwork: String = "$KAFKA_ALIAS:$KAFKA_INTERNAL_PORT"

    val mysqlJdbcUrlInNetwork: String =
        "jdbc:mysql://$MYSQL_ALIAS:3306/$MYSQL_DATABASE?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"

    /** Redis 키를 컨테이너 안의 redis-cli로 읽는다. 드라이버 의존성 없이 키 존재만 보면 된다. */
    fun redisKeys(pattern: String): List<String> {
        val result = redis.execInContainer("redis-cli", "--scan", "--pattern", pattern)
        return result.stdout.lines().filter { it.isNotBlank() }
    }

    override fun close() {
        listOf(qdrant, kafka, redis, mongo, mysql).forEach { it.stop() }
        network.close()
    }

    private fun initiateReplicaSet() {
        mongo.execInContainer(
            "mongosh", "--quiet", "--eval",
            "rs.initiate({_id:'$MONGO_REPLICA_SET',members:[{_id:0,host:'$MONGO_ALIAS:$MONGO_PORT'}]})"
        )
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        while (Instant.now().isBefore(deadline)) {
            val hello = mongo.execInContainer("mongosh", "--quiet", "--eval", "db.hello().isWritablePrimary")
            if (hello.stdout.trim() == "true") return
            Thread.sleep(500)
        }
        error("Mongo replica set did not elect a primary in 30s")
    }

    companion object {
        const val MYSQL_ALIAS = "mysql"
        const val MYSQL_DATABASE = "kachi"
        const val MYSQL_USER = "rgunny"
        const val MYSQL_PASSWORD = "rgunny"
        const val MONGO_ALIAS = "mongo"
        const val MONGO_PORT = 27017
        const val MONGO_REPLICA_SET = "rs0"
        const val REDIS_ALIAS = "redis"
        const val REDIS_PORT = 6379
        const val KAFKA_ALIAS = "kafka"
        const val KAFKA_INTERNAL_PORT = 19092
        const val QDRANT_ALIAS = "qdrant"
        const val QDRANT_HTTP_PORT = 6333
        const val QDRANT_GRPC_PORT = 6334
    }
}
