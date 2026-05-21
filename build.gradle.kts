// 루트 빌드 — 본 repo 는 Kotlin 만 사용하며 Java 파일은 추가하지 않는다.
// Java toolchain 은 JVM 21 (Kotlin 컴파일 결과물의 target).
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    id("org.springframework.boot") version "4.0.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    // OpenAPI spec build-time export — 실제 적용은 feed-bootstrap 모듈.
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0" apply false
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
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.13")
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
