plugins {
    kotlin("jvm")
    kotlin("plugin.spring")

    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "서비스 일곱 개를 컨테이너로 띄워 수집 -> story 조립 -> 요약 -> 라우팅 -> 발송 사이클을 끝까지 확인하는 e2e 테스트 (ADR 029)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// 실행할 서비스 모듈. 이 모듈은 각 모듈의 bootJar만 소비하고, 어느 모듈도 이 모듈에 의존하지 않는다.
val serviceModules = listOf(
    "user-service",
    "collector-service",
    "story-service",
    "ai-service",
    "notification-routing",
    "notification-service",
    "notification-worker",
)

dependencies {
    // 계약 타입으로 broker 레코드를 읽고 쓴다.
    testImplementation(project(":story-contract"))
    testImplementation(project(":ai-contract"))
    testImplementation(project(":notification-contract"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-logging")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    testImplementation("org.mongodb:mongodb-driver-sync")
    testImplementation("io.jsonwebtoken:jjwt-api:0.12.6")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    testRuntimeOnly("io.jsonwebtoken:jjwt-gson:0.12.6")

    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-mysql")
    // MySQLContainer가 기동 확인에 JDBC로 붙는다.
    testRuntimeOnly("com.mysql:mysql-connector-j")
}

// main 소스가 없다. 실행 가능한 jar를 만들 이유가 없다.
tasks.bootJar { enabled = false }
tasks.jar { enabled = false }

// 평소의 `./gradlew test`에는 끼지 않는다. 컨테이너 열두 개를 띄우는 데 몇 분이 걸려 커밋마다 도는 루프와 맞지 않는다(ADR 029).
tasks.test { enabled = false }

tasks.register<Test>("e2eTest") {
    description = "서비스 컨테이너 일곱 개로 수집부터 발송까지 전체 사이클을 돈다. Docker가 필요하다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }

    serviceModules.forEach { module ->
        dependsOn(":$module:bootJar")
        val bootJar = project(":$module").tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        inputs.files(bootJar)
        systemProperty("kachi.e2e.jar.$module", bootJar.get().archiveFile.get().asFile.absolutePath)
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
