// Application 모듈 — use case (suspend), port 인터페이스, 도메인 service.
// Spring stereotype 어노테이션 (@Service, @Component) 만 허용. Reactor 타입은 가능한
// 노출하지 않고 suspend / Flow 위주. 어쩔 수 없이 Mono/Flux 가 필요한 경우는 ADR-0001 의
// boundary 정책에 따른다.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":feed-domain"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")

    // Spring stereotype 어노테이션만 사용. 실제 런타임 (WebFlux / R2DBC 등) 은 adapter 모듈.
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("io.projectreactor:reactor-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
