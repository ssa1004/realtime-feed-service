# OWASP API Security Top 10 (2023) — realtime-feed-service 매핑

본 문서는 본 서비스의 보안 표면을 OWASP API Security Top 10 (2023) 카테고리로 매핑하고,
각 항목의 현재 대응 상태와 reactive 도메인 특유의 고려 사항을 정리합니다.

본 서비스는 Spring WebFlux + Kotlin Coroutines + WebSocket / SSE 구성이라 일반적인
servlet-blocking API 와 다른 표면이 있습니다 — 특히 long-lived connection,
backpressure, coroutine cancellation, Sinks subscriber 누수 같은 항목.

[SECURITY.md](../../SECURITY.md) 는 보고 절차와 SLA 만 다루고, 본 문서가 실제 항목별
점검 결과를 다룹니다.

---

## 범위와 한계

| 다루는 항목 | 범위 |
|---|---|
| `main` 브랜치의 코드 / 설정 / Helm chart | O |
| `infrastructure/docker-compose.yml` 의 dev 통합 환경 | O (best-effort) |
| 외부 의존 (auth-service, bid-ask-marketplace, Kafka cluster, PG/Redis 클러스터) | X — 각자의 보안 정책 |
| 운영 클러스터 (네트워크 / IAM / k8s RBAC) | X — 본 문서 범위 밖 |

대응 강도 표기:

| 표기 | 의미 |
|---|---|
| 대응 | 명시적 방어선이 코드/설정으로 존재 |
| 부분 대응 | 일부 방어선만 있고 잔여 위험 명시 |
| 미해당 | 도메인 특성상 적용되지 않음 |
| 미대응 | 인지하고 있으나 현재 미구현 (트레이드오프 명시) |

---

## API1:2023 — Broken Object Level Authorization (BOLA)

**상태: 미해당 (설계상)**

본 서비스가 다루는 데이터는 SKU 별 체결 / 호가 feed — 시장 데이터입니다. trader 개인의
주문 / 보유 / 정산은 본 서비스가 보지 않습니다 (그 책임은
[bid-ask-marketplace](https://github.com/ssa1004/bid-ask-marketplace) 와 별도 거래 서비스).

- `/api/v1/feed/{skuId}/recent`, `/window`, `/stream`, `/ws/feed/{skuId}`
  → 응답에 `traderId` / 주문자 식별자 없음
  ([FeedEventDto](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/dto/Dtos.kt) 참고).
- 즉 "다른 trader 의 데이터 접근" 표면 자체가 존재하지 않음.

향후 trader 단위 view (체결 알림 / 보유 SKU 의 호가 알림) 가 추가되면 본 카테고리가
유의미해짐 — 그 시점에 `TraderId` 추출 + path variable / query 의 trader 와 비교하는
authorization layer 추가.

---

## API2:2023 — Broken Authentication

**상태: 대응**

- prod 프로필: Spring Security Reactive + OAuth2 Resource Server 로 JWT 검증
  ([SecurityConfig.kt](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/security/SecurityConfig.kt)).
  JWK Set 은 `auth-service` 가 발행 (`OAUTH_JWK_SET_URI`).
- WebSocket / SSE 도 같은 `SecurityWebFilterChain` 안에서 보호 — handshake HTTP 요청
  단계에서 JWT 가 검증되므로 미인증 WS 업그레이드는 차단.
- dev 프로필은 `.anyExchange().permitAll()` — local docker-compose 통합 demo 전용.
  실수로 dev 가 prod 에서 활성화되지 않도록 Helm `springProfile` 의 prod 기본값을 명시
  ([values-prod.yaml](../../helm/realtime-feed-service/values-prod.yaml)).

### Reactive 도메인 특유 — 만료된 JWT 와 long-lived connection

- WebSocket / SSE 는 handshake 시점에 JWT 가 검증된 뒤 connection 이 hours 단위로 유지됨.
  → handshake 이후 JWT 가 revoke 되어도 connection 은 살아있음.
- 단기 완화: ingress / Helm `ingress.proxy-read-timeout` 으로 connection 의 max 수명
  강제 (현재 prod 1h 설정). client 가 재연결할 때 새 JWT 가 다시 검증된다.
- 진짜 revocation 까지 즉시 끊으려면 separate scheduled task 가
  `currentSubscriberCount` 기준 sweep — 향후 검토 (ADR-0007 의 "다시 검토할 시점").

---

## API3:2023 — Broken Object Property Level Authorization

**상태: 대응**

- 외부 노출 DTO 는
  [FeedEventDto](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/dto/Dtos.kt) /
  [WindowStatsDto](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/dto/Dtos.kt) 만.
- 도메인 [FeedEvent](../../feed-domain/src/main/kotlin/com/example/feed/domain/FeedEvent.kt) 의
  sealed interface 를 그대로 노출하지 않음 — adapter 가 `.from()` 으로 변환할 때 화이트리스트
  방식으로 필드 선택.
- 노출 필드: `type`, `skuId`, `occurredAt`, `sequence`, `tradeId`, `priceKrw`, `quantity` —
  모두 공개 시장 데이터.
- internal field (R2DBC PK `id` 같은 것) 는 DTO 에 없음.

---

## API4:2023 — Unrestricted Resource Consumption

⭐ **본 서비스의 보안 중심.** long-lived WebSocket / SSE 와 hot stream multicast 라는
도메인 특성상 자원 고갈이 가장 현실적인 위협.

**상태: 부분 대응 (잔여 위험 명시)**

### 현재 방어선

1. **per-SKU 버퍼 한도 (1024 events)** —
   [ReactorFeedSink](../../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/sink/ReactorFeedSink.kt)
   의 `onBackpressureBuffer(BUFFER_SIZE = 1024, false)`. slow consumer 가 따라오지
   못하면 emit 이 `FAIL_OVERFLOW` 로 떨어지고 publisher 는 계속 진행 (다른 subscriber
   에는 영향 X). 메모리 점유 예측 가능 (ADR-0007).

2. **publisher 측 backpressure sample (100ms)** —
   [StreamFeedUseCase](../../feed-application/src/main/kotlin/com/example/feed/application/usecase/StreamFeedUseCase.kt)
   의 `.sample(sampleWindowMs = 100)`. 클라이언트가 느리면 매 100ms 가장 최근 값만
   전달 (ADR-0003). 호가/체결 도메인 특성상 모든 이벤트 보다 최신값 우선.

3. **REST limit 강제** —
   [FeedRouter](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/web/FeedRouter.kt) `recent.limit` 은 `coerceIn(1, 1000)`,
   `window.minutes` 는 `coerceIn(1, 60)`.
   [QueryRecentFeedUseCase](../../feed-application/src/main/kotlin/com/example/feed/application/usecase/QueryRecentFeedUseCase.kt) 에서도 `require(limit in 1..1000)`.

4. **윈도우 통계 타임아웃 (5초)** —
   [ComputeWindowStatsUseCase](../../feed-application/src/main/kotlin/com/example/feed/application/usecase/ComputeWindowStatsUseCase.kt) 의
   `withTimeout(5_000)`. 잘못된 인덱스 / 큰 윈도우로 응답이 지연돼도 단일 요청이 5초
   이상 점유하지 않음.

5. **graceful shutdown** —
   `server.shutdown: graceful`, Helm `terminationGracePeriodSeconds: 60` + preStop
   sleep 10s. pod 종료가 in-flight WebSocket 을 폭력적으로 끊지 않음 (ADR-0010).

6. **Coroutine cancellation 전파** —
   [FeedWebSocketHandler](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/ws/FeedWebSocketHandler.kt) /
   [SseRouter](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/sse/SseRouter.kt) 에서 client disconnect → `session.send` 에러 → upstream Flux cancel →
   sink subscription 해제 (ADR-0006).

7. **Kafka consumer 격리** —
   [TradeMatchedConsumer](../../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/kafka/TradeMatchedConsumer.kt) 는
   `SupervisorJob() + Dispatchers.IO` 의 별도 scope. 한 메시지 처리 실패가 다른 메시지를
   끊지 않음. `@PreDestroy` 에서 scope.cancel().

### 잔여 위험과 향후 검토

| 위험 | 현재 상태 | 향후 조치 |
|---|---|---|
| **WebSocket connection 수 무제한** | 미대응 — 한 client 가 임의 수의 WS 연결을 만들 수 있음 | ingress / API GW 단의 per-IP 연결 한도 (nginx `limit_conn`, Envoy `connection_limit`). 애플리케이션 레이어에서 `currentSubscriberCount()` 기반 거절은 향후 검토 |
| **SSE / WS idle timeout** | 부분 — ingress `proxy-read-timeout: 3600` 만 적용 | Spring Boot reactive server (Netty) 자체의 `server.netty.idle-timeout` 명시는 향후 |
| **Kafka payload 크기** | 미대응 — `max.partition.fetch.bytes` 기본값 (1MB) | broker / consumer 양쪽에서 명시. payload 가 broker 정책으로 1MB 이상 못 들어오므로 application 의 OOM 표면은 제한적 |
| **Sinks subscriber 누수** | 부분 — disconnect → cancel 전파는 동작 (ADR-0006). 하지만 SKU 별 sink 객체는 GC 자동 X | 운영에서 `currentSubscriberCount() == 0` sink 의 주기적 cleanup job 필요 (ADR-0007 의 "다시 검토할 시점") |
| **R2DBC pool 고갈** | 부분 — `max-size: 10`, recent/window 쿼리는 빠름 | scrape 공격 시 동시 query 수가 pool 한도 초과 가능. ingress 단 per-IP rate limit 필요 |
| **JSON parse cost** | 부분 — Jackson 기본값 (max nesting 1000). 도메인 JSON 은 평탄해서 영향 적음 | 명시적 `MaxDocumentLength` 한도는 향후 검토 |
| **`recent`/`window` scraping** | 미대응 — 한 client 가 모든 SKU 의 recent 를 1초 단위로 polling 가능 | ingress / API GW 의 rate limit (per-IP, per-token). 본 서비스 레이어 추가는 YAGNI 검토 |

### Reactive 도메인 특유 — coroutine cancellation 누락의 의미

coroutine 이 cancel 되지 않으면:
- structured concurrency 가 깨져 child job 이 누수 (heap / thread 누적)
- Spring Boot graceful shutdown 이 끝나지 않음 → SIGKILL

방어선:
- [IngestTradeMatchedUseCase](../../feed-application/src/main/kotlin/com/example/feed/application/usecase/IngestTradeMatchedUseCase.kt) 는
  `coroutineScope { }` 안에서 `launch` 로 캐시/저장 호출 — 부모 cancel 시 자동 정리.
- [TradeMatchedConsumer](../../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/kafka/TradeMatchedConsumer.kt) 는
  `@PreDestroy` 에서 `scope.cancel()`.

이는 ADR-0006, ADR-0010 의 약속이며 본 항목의 핵심 방어선.

---

## API5:2023 — Broken Function Level Authorization

**상태: 대응 (이번 sweep 에서 강화)**

본 서비스에는 사용자 facing admin endpoint 가 존재하지 않습니다 — REST 는 `recent` /
`window` / `stream` 만, WS 는 `/ws/feed/{skuId}` 만.

**이번 sweep 에서 발견 및 수정한 사항:**
- `/actuator/**` 가 전체 `permitAll()` 이라 `/actuator/info`, `/actuator/metrics`,
  `/actuator/prometheus` 가 비인증으로 노출되었음. 이는 빌드 메타데이터 + 시스템 metric
  유출 (API5, API8).
- 수정: `/actuator/health` 와 `/actuator/health/**` (k8s 의 liveness / readiness probe
  만 사용하는 path) 만 `permitAll()`, 나머지 `/actuator/**` 는 `authenticated()`.

운영 클러스터에서 Prometheus scrape 는 NetworkPolicy + JWT 또는 mTLS 로 인증된
서비스만 접근. 본 sweep 이전에는 ingress 가 노출된 경우 누구나 접근 가능했음.

---

## API6:2023 — Unrestricted Access to Sensitive Business Flows

**상태: 부분 대응 (도메인 특성 명시)**

본 서비스가 노출하는 흐름은 시장 데이터 read. "민감한 비즈니스 흐름" (가격 영향 / 자동
거래 봇) 의 영향은 본 서비스가 아닌 거래 / 주문 서비스 쪽에 있습니다.

다만 본 서비스의 출력이 자동 거래 봇의 입력이 될 수 있어 — 봇이 본 service 에 N 만 개의
구독으로 시장 데이터를 빠르게 수집하는 패턴은 API4 의 자원 고갈과 겹칩니다.

| 위험 | 현재 | 향후 |
|---|---|---|
| WS / SSE 구독 폭증 (자동 봇) | 부분 — sink 별 buffer 한도로 한 구독자가 다른 구독자에 영향 X | ingress 단 per-token / per-IP 동시 connection 한도 |
| recent 엔드포인트 scraping | 부분 — `limit` 1~1000 강제 | rate limit |

본 항목은 도메인 (시장 feed 는 본질적으로 빠른 read 가 요구되는 데이터) 의 특성상 application
레이어에서 강하게 막기보다는 ingress / API GW 의 인프라 레벨에서 다루는 것이 적절.

---

## API7:2023 — Server Side Request Forgery (SSRF)

**상태: 미해당**

- 본 서비스가 외부 URL 을 호출하는 코드 경로:
  - `oauth2-resource-server` 의 JWK Set fetch — URL 은 `OAUTH_JWK_SET_URI` 환경변수
    (운영자 통제, 사용자 입력 X).
- 사용자 입력 (path variable `skuId`, query `limit` / `minutes` / Origin 헤더 / WS
  payload) 어디에서도 outbound URL 을 만들지 않음.

향후 webhook callback / sink-to-external 같은 기능이 추가되면 본 카테고리가 유의미해짐.
그 시점에 URL allowlist + IMDS / private CIDR 차단 검토.

---

## API8:2023 — Security Misconfiguration

**상태: 대응 (이번 sweep 에서 강화)**

### Reactive 도메인 특유 — WebSocket Origin / CORS

Spring WebFlux 의 WebSocket 핸들러는 servlet MVC 의 `AbstractHandshakeHandler` 와
달리 기본 `Origin` 검증이 **없습니다**. 즉 임의 origin 의 페이지가 사용자 브라우저를
통해 본 서비스로 WebSocket / SSE 연결을 만들 수 있고, 이는 cross-site WebSocket
hijacking 의 표면이 됩니다.

**이번 sweep 에서 추가한 방어선:**
- [WebSocketOriginFilter](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/security/WebSocketOriginFilter.kt) — `/ws/feed/**` 와
  `/api/v1/feed/{sku}/stream` (SSE) 에 한해 `Origin` 헤더를 검사.
  - `feed.security.allowed-origins` 화이트리스트의 origin 만 통과.
  - `Origin` 헤더가 없는 server-to-server 호출은 통과 (JWT 만으로 검증).
  - 그 외는 403.
- prod values 의 기본 allowlist 는 `https://feed.example.com` — 운영 ingress 도메인.

### 기타 misconfig 점검

| 항목 | 상태 |
|---|---|
| CSRF | 의도적 disable — REST API + JWT (cookie 미사용) 이므로 OK |
| Form login / HTTP Basic | 명시적 disable |
| HTTP → HTTPS | ingress (cert-manager) 가 처리 ([values-prod.yaml](../../helm/realtime-feed-service/values-prod.yaml)) |
| 컨테이너 보안 | runAsNonRoot, readOnlyRootFilesystem, drop ALL caps, seccomp RuntimeDefault |
| Secret 관리 | values 의 평문 password 금지, existingSecret 패턴 |
| Stack trace 노출 | Spring Boot 의 reactive 기본 — body 에 trace 안 실음. 운영에서 한 번 더 확인 권장 |
| Default credentials | DB / Redis 에 default 자격은 docker-compose dev 에만 (`feed/feed`). prod 는 secret 강제 |
| Verbose error | `logging.level` 의 root: INFO, com.example.feed: DEBUG — prod 에서 DEBUG 는 PII 가 없는 도메인이라 무해하나 향후 INFO 로 좁히는 것 검토 |

---

## API9:2023 — Improper Inventory Management

**상태: 대응**

- REST endpoint 는 모두 `/api/v1/...` 로 명시적 버전 prefix.
- WebSocket 는 `/ws/feed/{skuId}` — 버전 prefix 가 없지만 단일 path 라 inventory 측면의
  드리프트 위험은 낮음. v2 가 생기면 `/ws/v2/feed/...` 로 분리.
- 본 service 의 API surface 가 작아 (REST 2 개 + SSE 1 개 + WS 1 개) — 별도 API
  catalog 없이 `feed-adapter-in/src/main` 만 검토하면 전체 표면 파악 가능.
- Backstage [catalog-info.yaml](../../catalog-info.yaml) 으로 service catalog 메타데이터 등록.
- Helm chart 가 운영 / dev / test 환경 차이를 명시.

### 잔여 — host / 환경별 차이

- dev profile (`dev`) 은 모든 endpoint 가 비인증 — 운영 클러스터에 절대 dev profile
  가 활성화되지 않도록 Helm values 단에서 `springProfile: prod` 명시.

---

## API10:2023 — Unsafe Consumption of APIs

**상태: 부분 대응**

본 서비스가 신뢰하는 외부 입력:

1. **Kafka `market.tradematched` 메시지** —
   [TradeMatchedConsumer](../../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/kafka/TradeMatchedConsumer.kt) 가 parse.
   - 현재: `try / catch` 로 감싸고 실패 시 skip + offset commit (at-least-once
     단순화, DLQ 없음).
   - 도메인 invariant 검증: `SkuId` 의 길이 / 공백, `Money` 의 음수 금지,
     `quantity >= 1` 등은 도메인 value class 가 `require()` 로 강제.
   - 잔여 위험: JSON parse 후 도메인 변환 실패가 동일하게 skip — log 만 남고 metric 으로
     올라가지 않음. DLQ / retry topic 패턴 도입은 ADR-0004 의 향후 항목.

2. **`auth-service` JWK Set** — 신뢰. 대안 (JWK 의 강제 회전 / 만료 cache TTL) 은
   `spring-security-oauth2-resource-server` 의 default 동작에 위임.

3. **PostgreSQL / Redis** — 같은 클러스터의 내부 의존. NetworkPolicy 로 같은 namespace
   pod 만 접근 가능 (values-prod.yaml).

### Reactive 도메인 특유 — Kafka consumer 의 backpressure

Reactor Kafka 의 `KafkaReceiver.receive()` 는 backpressure 를 honor 합니다 — downstream
처리가 느리면 poll 이 자연스럽게 느려져 broker 측에서 lag 가 늘어남.
[TradeMatchedConsumer](../../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/kafka/TradeMatchedConsumer.kt) 의
처리는 `IngestTradeMatchedUseCase` 호출이 끝난 뒤 offset commit — at-least-once 보장.

Kafka consume lag 자체가 자원 고갈 시그널이므로 운영 metric 으로 노출:
- `kafka.consumer.records-lag-max` (Micrometer 기본 binding)

---

## 점검 결과 요약

이번 sweep 에서 신규 발견 및 수정:

| ID | 이슈 | 수정 |
|---|---|---|
| API5 / API8 | `/actuator/**` 전체 `permitAll` 로 metrics / info 비인증 노출 | [SecurityConfig](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/security/SecurityConfig.kt) 에서 `/actuator/health/**` 만 공개, 나머지 인증 강제 |
| API8 | WebFlux WebSocket / SSE 의 Origin 검증 부재 | [WebSocketOriginFilter](../../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/security/WebSocketOriginFilter.kt) 신규, `feed.security.allowed-origins` 화이트리스트 |

기존에 잘 대응되어 있던 영역:

- API2 (Spring Security Reactive + OAuth2 Resource Server JWT)
- API3 (DTO 화이트리스트, 도메인 sealed interface 와의 분리)
- API4 의 일부 (sink buffer 한도, sample backpressure, cancellation 전파)
- API9 (REST 버전 prefix, Helm chart 환경 분리)

잔여 위험 (의도된 트레이드오프 또는 운영 인프라가 다룰 영역):

- API4 의 connection 수 / rate limit / scraping — ingress / API GW 단의 책임
- API6 의 자동 봇 — 본질적으로 빠른 read 도메인, ingress 레이어에서 처리
- API10 의 Kafka payload schema — broker 측 정책 + 도메인 invariant 로 1차 방어
