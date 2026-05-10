// 루트 빌드 — 100% Kotlin reactive. 본 repo 는 Java 코드를 의도적으로 0 줄 유지.
// Java toolchain 은 JVM 21 만 사용 (Kotlin 컴파일 결과물의 target).
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.example.feed"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "io.spring.dependency-management")

    // 모든 모듈에 JVM toolchain 21 강제. Kotlin 만 쓰므로 java plugin 은 각 모듈이 java-library
    // 형태로 적용 (kotlin("jvm") 이 java plugin 을 자동 끌어옴).
    plugins.withId("org.jetbrains.kotlin.jvm") {
        the<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>().apply {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xjsr305=strict",
                    "-Xcontext-receivers", // ADR-0010 참고. context(Tx) receivers.
                )
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")
        }
        dependencies {
            // Reactor Kafka 는 Spring Boot BOM 에 포함되지 않아 명시 관리.
            dependency("io.projectreactor.kafka:reactor-kafka:1.3.23")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
