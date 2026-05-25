// Inbound adapter — Spring WebFlux (functional routing) + WebSocket + SSE + JWT 검증 filter.
// coRouter { } DSL 로 모든 endpoint 를 suspend 로 작성. WebSocket / SSE 도 Flow 로 받는다.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":feed-application"))
    implementation(project(":feed-domain"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // OpenAPI — WebFlux 용 springdoc. /v3/api-docs 노출 (functional routing 기반이라
    // -webflux-api 만; Swagger UI 는 미포함). build-time spec export 에 사용.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-api:3.0.3")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
