package com.example.feed

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication

/**
 * 스모크 테스트 — `RealtimeFeedApplication` 클래스가 로딩 가능하고 main 함수가 존재하는지만 검증.
 *
 * 본 레포의 진짜 컨텍스트 부팅은 R2DBC PostgreSQL / Redis / Kafka 인프라가 있어야 하므로
 * unit test 단계에서 `@SpringBootTest` 전체 부팅은 의미가 작다. 인프라가 갖춰진 통합 환경은
 * `infrastructure/docker-compose.yml` + `scripts/integration-demo.sh` 로 검증한다.
 */
class RealtimeFeedApplicationSmokeTest {

    @Test
    fun `main 클래스가 SpringApplication 으로 등록 가능하다`() {
        val app = SpringApplication(RealtimeFeedApplication::class.java)
        assertThat(app.allSources).contains(RealtimeFeedApplication::class.java)
    }
}
