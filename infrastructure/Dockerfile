# 멀티 스테이지 — JDK 21 build → JRE 21 runtime. 100% Kotlin 이지만 toolchain 은 JVM.
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# 의존성 캐시 — gradle 파일만 먼저 복사
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties /app/
COPY gradle /app/gradle
COPY feed-domain/build.gradle.kts /app/feed-domain/
COPY feed-application/build.gradle.kts /app/feed-application/
COPY feed-adapter-in/build.gradle.kts /app/feed-adapter-in/
COPY feed-adapter-out/build.gradle.kts /app/feed-adapter-out/
COPY feed-bootstrap/build.gradle.kts /app/feed-bootstrap/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 빌드
COPY feed-domain/src /app/feed-domain/src
COPY feed-application/src /app/feed-application/src
COPY feed-adapter-in/src /app/feed-adapter-in/src
COPY feed-adapter-out/src /app/feed-adapter-out/src
COPY feed-bootstrap/src /app/feed-bootstrap/src

RUN ./gradlew --no-daemon :feed-bootstrap:bootJar -x test

# ----- runtime
FROM eclipse-temurin:21-jre-alpine

# non-root user
RUN addgroup -g 10001 feed && adduser -D -u 10001 -G feed feed
USER feed

WORKDIR /app

COPY --from=build /app/feed-bootstrap/build/libs/*.jar /app/app.jar

# Spring Boot 의 graceful shutdown 활성화는 application.yml 에서 이미 함.
# JVM 옵션은 운영에서 환경변수 JAVA_OPTS 로 주입.
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

EXPOSE 8080
