// 도메인 모듈 — 외부 의존 0. Spring / Reactor / Kafka 모두 금지.
// 순수 Kotlin 으로 도메인 모델, 값 객체, 도메인 규칙만 담는다.
// kotlinx-coroutines 는 Flow 라는 도메인 어휘를 위해 일부러 허용 (선택적, ADR-0001 참고).
plugins {
    kotlin("jvm")
}

dependencies {
    // 도메인 어휘로서의 Flow 만 허용 — Reactor 타입은 절대 import 금지.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
