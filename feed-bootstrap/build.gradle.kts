// Bootstrap — Spring Boot main + application.yml + bean wiring.
// 다른 4 모듈을 runtime 으로 묶고 실행 가능한 jar 를 만든다.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    // OpenAPI spec build-time export — generateOpenApiDocs 가 앱을 부팅한 뒤
    // /v3/api-docs 를 fetch 해 docs/openapi/realtime-feed-service.yaml 로 떨어뜨린다.
    id("org.springdoc.openapi-gradle-plugin")
}

dependencies {
    implementation(project(":feed-domain"))
    implementation(project(":feed-application"))
    implementation(project(":feed-adapter-in"))
    implementation(project(":feed-adapter-out"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")
    // main 함수의 Hooks.enableAutomaticContextPropagation() (ADR-0005) — 명시적 의존.
    implementation("io.projectreactor:reactor-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// OpenAPI spec export 설정 — ./gradlew :feed-bootstrap:generateOpenApiDocs.
// 플러그인이 bootRun 으로 앱을 띄우고 apiDocsUrl 을 fetch 해 outputFileName 으로 저장한다.
// 앱 부팅에 Postgres(R2DBC) / Kafka / Redis 가 필요하므로 로컬 단독 실행보다는 CI 에서
// docker compose 와 함께 돌리는 것을 권장 (docs/openapi/README.md 참고).
openApi {
    apiDocsUrl.set("http://localhost:8080/v3/api-docs.yaml")
    outputDir.set(layout.projectDirectory.dir("../docs/openapi"))
    outputFileName.set("realtime-feed-service.yaml")
    waitTimeInSeconds.set(120)
}
