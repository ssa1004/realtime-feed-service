# Realtime Feed Service

리셀 마켓의 실시간 호가/체결 feed 를 WebSocket 과 SSE 로 fan-out 하는 백엔드입니다.
[`resell-orderbook`](https://github.com/ssa1004/resell-orderbook) 이 발행하는
체결 이벤트를 Kafka 로 consume 해서, SKU 단위 hot stream 으로 multicast 합니다.

portfolio 안의 다른 레포는 Spring MVC + JPA 인 반면, 본 레포는 Kotlin coroutines +
Spring WebFlux 로 구성되어 있습니다 — "언제 reactive 를 쓰지 말아야 하는가" 의 기준은
[ADR-0008](docs/adr/0008-when-not-to-use-reactive.md) 에 정리되어 있습니다.

## 기술 스택

- **Language**: Kotlin 2.0, Java 21 toolchain
- **Framework**: Spring Boot 3.4, Spring WebFlux (functional `coRouter`)
- **Concurrency**: Kotlin Coroutines (`suspend` / `Flow` / `StateFlow` / `Channel`),
  Project Reactor (`Mono` / `Flux` / `Sinks`)
- **DB**: PostgreSQL (R2DBC), Redis Reactive (Lettuce)
- **Messaging**: Apache Kafka (Reactor Kafka)
- **Push**: WebSocket + Server-Sent Events
- **Security**: Spring Security Reactive (OAuth2 Resource Server, JWT)
- **Test**: StepVerifier, kotlinx-coroutines-test
- **Build**: Gradle Kotlin DSL (`*.gradle.kts` only)

## 도메인

본 service 가 다루는 핵심 흐름:

1. `resell-orderbook` 의 거래 매칭이 Kafka topic `market.tradematched` 로 발행됨
2. 본 service 의 Kafka consumer 가 receive → 도메인 [`FeedEvent.TradeMatched`](feed-domain/src/main/kotlin/com/example/feed/domain/FeedEvent.kt)
   로 변환
3. SKU 별 hot stream (`Sinks.many().multicast().onBackpressureBuffer`) 에 emit
4. 그 SKU 를 구독 중인 WebSocket / SSE 클라이언트가 즉시 받음 (sample 100ms backpressure)
5. 동시에 Redis SortedSet 에 캐시 (catch-up 용), R2DBC 에 영속

```mermaid
sequenceDiagram
    autonumber
    participant RB as resell-orderbook
    participant K as Kafka
    participant Feed as realtime-feed-service
    participant DB as Postgres (R2DBC)
    participant R as Redis
    participant WS as WebSocket Client

    RB-->>K: market.tradematched (체결 이벤트)
    K->>Feed: TradeMatchedConsumer (Reactor Kafka)
    Feed->>Feed: IngestTradeMatchedUseCase
    par fan-out
        Feed-->>WS: ReactorFeedSink → multicast (sample 100ms)
    and 캐시
        Feed->>R: ZADD feed:recent:{sku} score=sequence
    and 영속
        Feed->>DB: INSERT feed_events
    end

    Note over WS,Feed: 클라이언트 disconnect → upstream 자동 cancel (ADR-0006)
```

## 모듈 구조

헥사고날 (ports & adapters) 을 5 모듈로 분리. 의존 방향: `domain ← application ← adapter-* ← bootstrap`.

```mermaid
graph LR
    in[feed-adapter-in<br/>WebFlux + WS + SSE + JWT]
    app[feed-application<br/>use case + port]
    domain[feed-domain<br/>도메인 + 값 객체]
    out[feed-adapter-out<br/>Kafka + R2DBC + Redis + Sink]
    boot[feed-bootstrap<br/>Spring Boot main]

    in --> app
    out --> app
    app --> domain
    boot --> in
    boot --> out
```

| 모듈 | 책임 |
|---|---|
| `feed-domain` | 순수 Kotlin 도메인. `SkuId`, `Money` (value class), `FeedEvent` (sealed interface), `FeedWindow` (집계). Spring 의존성 0 |
| `feed-application` | use case (`suspend fun`), port 인터페이스. Reactor 타입은 시그니처에서 노출 X (ADR-0001) |
| `feed-adapter-in` | WebFlux `coRouter` + WebSocket + SSE + Spring Security Reactive (JWT) |
| `feed-adapter-out` | Reactor Kafka consumer, R2DBC PostgreSQL, Reactive Redis (Lettuce), `Sinks.many().multicast()` 기반 fan-out |
| `feed-bootstrap` | Spring Boot main + `application.yml` + bean wiring |

## Quick Start

### 단일 모듈 단위 테스트

```bash
./gradlew check                   # 빌드 + 모든 테스트
./gradlew :feed-domain:test       # 도메인 단위
```

### 로컬 통합 시연 (docker-compose)

postgres + redis + kafka + auth-stub + 본 앱이 한 머신에서 뜹니다.

```bash
# 1. 통합 환경 기동
docker compose -p feed-integration -f infrastructure/docker-compose.yml up -d --build

# 2. mock JWT 발급 → SKU 구독 → mock 거래 이벤트 생성 → WebSocket / SSE 수신 시연
./scripts/integration-demo.sh

# 3. 정리
docker compose -p feed-integration -f infrastructure/docker-compose.yml down -v
```

`-p` 로 compose project 이름을 명시 — 같은 머신에서 다른 portfolio repo 의 compose 와
동시에 띄울 때 이름 / 포트 충돌을 피합니다 (본 앱: `8088`).

## API

### REST

| method | path | 설명 |
|---|---|---|
| `GET` | `/api/v1/feed/{skuId}/recent?limit=N` | 최근 N 건 조회 (catch-up 용). cache hit / store fallback |
| `GET` | `/api/v1/feed/{skuId}/window?minutes=M` | 최근 M 분 윈도우 통계 (volume / VWAP / high / low) |
| `GET` | `/api/v1/feed/{skuId}/stream` | SSE — `text/event-stream` |

### WebSocket

| path | 설명 |
|---|---|
| `/ws/feed/{skuId}` | text frame 으로 [`FeedEventDto`](feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/dto/Dtos.kt) JSON |

브라우저에서:
```javascript
const sse = new EventSource('/api/v1/feed/NIKE-DUNK-LOW-001/stream');
sse.addEventListener('TradeMatched', e => console.log(JSON.parse(e.data)));

const ws = new WebSocket('ws://localhost:8088/ws/feed/NIKE-DUNK-LOW-001');
ws.onmessage = e => console.log(JSON.parse(e.data));
```

## 주요 설계 결정

자세한 배경은 [docs/adr/](docs/adr/) 의 ADR 10건에 정리되어 있습니다.

| ADR | 주제 | 핵심 |
|---|---|---|
| [0001](docs/adr/0001-webflux-coroutines-boundary.md) | WebFlux ↔ Coroutines boundary | suspend / Flow 만 노출, Reactor 타입은 adapter 안에서만 |
| [0002](docs/adr/0002-bounded-elastic-isolation.md) | blocking 격리 | `boundedElastic` 의 한도 / elastic 동작 / `parallel` 과의 차이 |
| [0003](docs/adr/0003-backpressure-strategy.md) | backpressure 정책 | `sample` (client-facing) / `bufferTimeout` (batch) / `window` (통계) |
| [0004](docs/adr/0004-error-handling.md) | error handling | `onErrorResume` / `retryWhen` / `onErrorContinue` 금지 |
| [0005](docs/adr/0005-context-propagation.md) | 컨텍스트 전파 | Reactor Context ↔ CoroutineContext + MDC + Micrometer |
| [0006](docs/adr/0006-cancellation-propagation.md) | cancellation 전파 | WebSocket disconnect → upstream Kafka subscription cancel |
| [0007](docs/adr/0007-hot-stream-multicast.md) | hot stream multicast | `multicast()` + 별도 cache vs `replay(N)` trade-off |
| [0008](docs/adr/0008-when-not-to-use-reactive.md) | 언제 reactive 를 쓰지 말아야 하나 | CPU bound / 단순 CRUD / JPA 종속은 imperative 가 낫다 |
| [0009](docs/adr/0009-r2dbc-vs-jpa.md) | R2DBC vs JPA | 본 도메인이 R2DBC 인 이유 + R2DBC 의 한계 |
| [0010](docs/adr/0010-coroutines-structured-concurrency.md) | structured concurrency | `coroutineScope` vs `supervisorScope` + `withTimeout` 정책 |

## 운영 프로필 (`prod`)

`SPRING_PROFILES_ACTIVE=prod` 일 때 활성화되는 항목입니다.

- PostgreSQL / Redis / Kafka 실제 사용
- Spring Security Reactive 가 [auth-service](https://github.com/ssa1004/auth-service)
  의 JWK Set 으로 JWT 강제 검증
- Reactor Kafka consumer 활성 (`feed.kafka.enabled=true`)
- WebFlux graceful shutdown (`server.shutdown=graceful`)

dev 프로필은 인증을 우회하고 Kafka consumer 도 비활성 — 단순 endpoint 동작 시연용.

## 인프라

- `infrastructure/Dockerfile` — multi-stage 빌드 (JDK 21 build → JRE 21 distroless), non-root.
- `infrastructure/docker-compose.yml` — 로컬 통합 환경 (postgres + redis + kafka + 본 앱).
- `helm/realtime-feed-service/` — Kubernetes 배포용 Helm chart (`values.yaml` + `values-prod.yaml`).
- `.github/workflows/ci.yml` — 단위 테스트 → 정적 분석 → 이미지 빌드 + Trivy 스캔 (계획).

### Helm chart

```bash
helm lint helm/realtime-feed-service
helm lint helm/realtime-feed-service -f helm/realtime-feed-service/values-prod.yaml
helm template feed ./helm/realtime-feed-service -n market

# 운영 배포
helm upgrade --install realtime-feed-service ./helm/realtime-feed-service \
  -n market --create-namespace \
  -f helm/realtime-feed-service/values-prod.yaml \
  --set image.tag=$(git rev-parse --short HEAD)
```

## 테스트

| 모듈 | 항목 | 검증 대상 |
|---|---|---|
| `feed-domain` | Money, FeedWindow, BidAskSnapshot | 도메인 invariants, VWAP, spread |
| `feed-application` | IngestTradeMatched, StreamFeed, ComputeWindowStats | 부수효과 격리, catch-up + realtime merge, 윈도우 집계 |
| `feed-adapter-in` | FeedRouter, FeedWebSocketHandler | REST 응답, WebSocket text frame, path 검증 |
| `feed-adapter-out` | ReactorFeedSink (StepVerifier 포함) | multicast fan-out, SKU 격리, mono { } boundary |
| `feed-bootstrap` | Application smoke | main class 등록 |

## Portfolio Set 통합

이 레포는 단독으로도 동작하지만, 같은 사용자가 운영하는 백엔드 레포들이 한 시스템처럼
맞물리는 구성의 일부입니다. 프로필 README:
<https://github.com/ssa1004>.

### 인접 레포와의 관계

| 레포 | 역할 | 본 레포와의 관계 |
|---|---|---|
| `auth-service` | 사용자 인증 + JWT 발급 | 본 레포가 JWK Set 으로 들어오는 JWT 를 검증 |
| `security-log-search` | 보안 로그 수집/검색 | 본 레포의 인증 실패 / 권한 위반 로그를 인덱싱 (선택) |
| `notification-hub` | 다채널 알림 | 본 레포는 직접 호출 없음 — 알림은 `resell-orderbook` 이 담당 |
| `search-service` | 상품 검색 | 본 레포가 수집한 체결 이력을 검색에 노출 (향후) |
| `billing-platform` | 사용량 과금 | WebSocket 활성 connection 수를 usage 로 전송 (향후) |
| `resell-orderbook` | 한정판 리셀 마켓 백엔드 | 본 레포의 upstream — `market.tradematched` 토픽을 본 레포가 consume |
| `gpu-job-orchestrator` | GPU job 스케줄러 | 본 레포는 직접 통합 없음 |
| `mini-shop-observability` | 관측 스택 | 본 레포의 metrics / trace / log 수집 |

### 들어오는 / 나가는 통합점

- **들어오는** — `auth-service` JWT 로 인증된 클라이언트가 SSE / WebSocket 구독.
  Kafka 의 `market.tradematched` topic 을 `resell-orderbook` 으로부터 consume.
- **나가는** — Micrometer Observation 의 trace 가 `mini-shop-observability` 의 Tempo 로
  전송 (Reactor Hooks.enableAutomaticContextPropagation 으로 자동 전파, ADR-0005).

### 사용자 라이프사이클 sequence (호가 → 체결 → 실시간 feed)

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant Auth as auth-service
    participant RB as resell-orderbook
    participant K as Kafka
    participant Feed as realtime-feed-service
    participant Browser as 브라우저

    U->>Auth: 로그인
    Auth-->>U: JWT (Bearer)

    Browser->>Feed: WebSocket /ws/feed/{sku} (Authorization: Bearer)
    Feed->>Feed: JWT 검증 (auth-service JWK Set)
    Feed->>Feed: ReactorFeedSink.subscribe(sku)

    U->>RB: POST /api/v1/listings (ASK)
    RB->>RB: 매칭 → Trade INSERT + Outbox INSERT
    RB-->>K: market.tradematched

    K->>Feed: TradeMatchedConsumer (Reactor Kafka)
    Feed->>Feed: IngestTradeMatchedUseCase
    Feed-->>Browser: 즉시 push (sample 100ms backpressure)
```

### 통합 시연 — 외부 의존을 mock 으로 닫고 한 머신에서 한 사이클

```bash
# 1. 통합 환경 기동
docker compose -p feed-integration -f infrastructure/docker-compose.yml up -d --build

# 2. mock JWT 발급 → SSE / WebSocket 으로 구독 → mock 체결 이벤트 → 수신 시연
./scripts/integration-demo.sh

# 3. 정리
docker compose -p feed-integration -f infrastructure/docker-compose.yml down -v
```

실제 `auth-service` / `resell-orderbook` 대신 같은 계약을 충족하는 stub 으로 닫혀 있어
한 머신에서 한 사이클을 마칠 수 있습니다.

## 향후 개선 사항

- BidAskSnapshot 의 push (호가창 변동 이벤트) — 현재는 TradeMatched 만.
- WebSocket 의 client-driven backpressure — flow control 메시지 도입.
- 다중 instance scale-out — sink 가 instance 별로 분리되므로 SKU sharding 또는 Redis
  pub/sub 로 instance 간 fan-out 동기화 필요.
- Trivy / dependency-track 기반 SBOM 자동 생성.
