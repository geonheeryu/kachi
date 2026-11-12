package me.rgunny.kachi.e2e.support

import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 서비스 모듈의 bootJar를 JDK 컨테이너에 복사해 실행한다.
 *
 * 이미지를 빌드하지 않는다. jar 경로는 Gradle `e2eTest` task가 `kachi.e2e.jar.<module>` 시스템 프로퍼티로 넘긴다.
 * 프로파일은 `local`이고 인프라 주소와 토글은 env로 덮는다. 기동 완료는 `/actuator/health` 200으로 본다.
 * 컨테이너 로그는 `e2e.<module>` 로거로 흘려 실패 시 원인을 볼 수 있게 한다.
 */
class KachiServiceContainer(
    val module: String,
    val port: Int,
    network: Network,
    environment: Map<String, String>
) : GenericContainer<KachiServiceContainer>(BASE_IMAGE) {

    init {
        val jar = jarPathOf(module)
        withCopyFileToContainer(MountableFile.forHostPath(jar), JAR_PATH)
        // JVM 옵션은 JAVA_TOOL_OPTIONS가 아니라 명령줄로 준다. env로 주면 JVM이 stderr에 `Picked up ...`을 찍어 ERROR 로그가 남는다.
        withCommand("java", *JVM_OPTIONS, "-jar", JAR_PATH)
        withEnv("SPRING_PROFILES_ACTIVE", "local")
        environment.forEach { (name, value) -> withEnv(name, value) }
        withNetwork(network)
        withNetworkAliases(module)
        withExposedPorts(port)
        withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("e2e.$module")).withSeparateOutputStreams())
        waitingFor(
            Wait.forHttp("/actuator/health")
                .forPort(port)
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT)
        )
    }

    /** 테스트 JVM이 붙는 주소. */
    val baseUrl: String
        get() = "http://$host:${getMappedPort(port)}"

    /** 같은 network의 다른 서비스가 붙는 주소. */
    val urlInNetwork: String = "http://$module:$port"

    /** 컨테이너 로그의 마지막 [lines]줄. 컨테이너가 만들어지지 않았으면 빈 문자열이다. */
    fun logTail(lines: Int): String {
        if (containerId == null) return ""
        return logs.lines().takeLast(lines).joinToString("\n")
    }

    private companion object {
        // 로컬 개발 JDK(`.sdkmanrc`의 Corretto 21)와 같은 배포판. Corretto는 JRE 이미지를 내지 않아 headless JDK가 가장 작다.
        const val BASE_IMAGE = "amazoncorretto:21-al2023-headless"
        const val JAR_PATH = "/app/app.jar"
        // 함께 뜨는 JVM 여섯 개가 CPU를 나눠 쓴다. JIT 단계를 낮추고 힙을 제한해 기동을 앞당긴다.
        val JVM_OPTIONS = arrayOf("-Xmx512m", "-XX:TieredStopAtLevel=1", "-Dspring.jmx.enabled=false")
        val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(4)

        fun jarPathOf(module: String): Path {
            val property = "kachi.e2e.jar.$module"
            val path = System.getProperty(property)
                ?: error("시스템 프로퍼티 $property 가 없다. `./gradlew :e2e-test:e2eTest`로 실행해야 bootJar 경로가 넘어온다.")
            return Path.of(path).also { require(Files.isRegularFile(it)) { "bootJar가 없다: $it" } }
        }
    }
}
