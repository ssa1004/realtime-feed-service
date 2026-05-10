# ADR-0009: R2DBC vs JPA

## 상태
적용

## 배경
PostgreSQL 영속이 필요한 본 레포의 선택지는 둘.

- **JPA + JDBC** — 성숙. caching, lazy loading, dirty checking. 단 connection 을 호출
  종료까지 점유 (synchronous).
- **R2DBC** — non-blocking. 코틀린 coroutines / Reactor 와 자연스러운 통합. 단 JPA 의
  많은 기능 미지원.

본 레포는 reactive 전반 (WebSocket fan-out + Kafka consume) 이라는 도메인 특성 때문에
영속도 reactive 로 통일하는 게 자연스럽다. 다만 R2DBC 의 한계를 명시해야 잘못된 기대를
피할 수 있다.

## 결정

PostgreSQL 영속은 R2DBC + Spring Data R2DBC `DatabaseClient` 를 쓴다.

### 본 도메인이 R2DBC 를 정당화하는 이유

1. **Kafka consumer + sink fan-out 이 메인 hot path** — 영속은 *부수효과*. 영속 호출이
   JDBC 라면 boundedElastic 으로 격리해야 하고, 격리해도 connection 점유 시간이 길어
   throughput 이 코너에서 떨어진다. R2DBC 면 같은 event-loop 위에서 await.
2. **단순 INSERT + 시간 범위 SELECT** — JPA 의 advanced 기능 (cascading, dirty checking,
   association mapping) 이 거의 필요 없음. R2DBC 의 minimal API 로 충분.
3. **WebSocket fan-out 의 응답성** — 영속이 blocking 이면 한 connection 당 thread 가
   잠시 점유, multicast 의 내부 spin 이 느려질 수 있다 (실측 X, 이론적 우려).

### R2DBC 의 한계 (본 repo 가 받아들이는 것)

- **caching 없음** — 1차 / 2차 캐시 없음. Redis 로 별도 cache (이미 본 repo 가 채택).
- **lazy loading 없음** — eager only. 본 repo 의 도메인이 단순 (`feed_events` 단일 테이블)
  이라 영향 0.
- **association mapping 없음** — 한 행 = 한 도메인 객체. JPA 의 `@OneToMany` 같은 것 X.
- **transaction propagation 의 한정성** — `@Transactional` 의 일부 옵션 (예: NESTED) 이
  R2DBC 와 호환되지 않거나 제한적.
- **마이그레이션 도구 호환성** — Flyway 는 JDBC. R2DBC 만 쓰면 Flyway 는 별도 datasource
  를 만들어야 한다 (Spring Boot 가 일부 자동화).
- **dynamic query** — JPA Criteria 같은 type-safe builder 가 없음. 본 repo 는 raw SQL
  로 충분.

### Datasource 듀얼 (Flyway 용 JDBC)

운영 schema migration 은 Flyway 가 JDBC 로 돌고, 런타임은 R2DBC 가 돈다. `feed-bootstrap`
의 `application.yml` 에 두 datasource 모두 정의. dev / test 에서는 in-memory 또는
testcontainers 로.

### 비교 매트릭스

| 측면 | JPA + JDBC | R2DBC |
|---|---|---|
| sync/async | sync (HikariCP) | async (Reactor) |
| caching | 1차/2차 | X (Redis 별도) |
| lazy loading | yes | X |
| association | yes | X |
| transaction | 풍부 | 한정 |
| dynamic query | Criteria | raw SQL |
| 도구 (Flyway) | native | 별도 datasource |
| 학습 곡선 | 가파름 | 단순 |

본 repo 는 단순함의 가치가 더 커서 R2DBC.

## 결과
- 단순 INSERT / SELECT 만 다루는 본 repo 의 영속 layer 가 deadly simple.
- reactive boundary 를 깨지 않음.
- (단점) Flyway 듀얼 datasource 로 의존성 증가.
- (단점) 미래에 도메인 복잡도가 늘어 association 이 필요해지면 큰 변경 비용.

## 다시 검토할 시점
- 도메인이 association / aggregate root 를 가져야 할 만큼 복잡해질 때 — JPA 로 마이그
  비용 vs R2DBC 위에서 수기 관리 비용 비교.
- R2DBC 가 JPA 의 일부 기능 (예: simple association) 을 제공하기 시작할 때.
