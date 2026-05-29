# 백엔드 스킬 인덱스 — 이 레포에서 무엇을 배우나

> 이 레포가 시연하는 **reactive 백엔드 패턴**을 `무엇 → 이 레포 어디서(코드) → 왜(ADR) → 더 깊은 이론(dev-lab)` 으로 잇는 학습용 인덱스.
> "이 패턴 공부하려면 어디부터 보나"의 진입점. 설명을 다시 쓰지 않고 코드·결정·이론으로 연결만 한다.
>
> 도메인: **리셀 마켓의 실시간 호가/체결 feed 를 WebSocket / SSE 로 fan-out**. portfolio 의 다른 레포가 Spring MVC + JPA 인 반면, 본 레포만 100% Kotlin Coroutines + Spring WebFlux + R2DBC + Reactor Kafka. 그래서 여기서 배울 것은 "reactive 를 *제대로* 쓰는 법" 과 "**언제 쓰지 말아야 하는가**" 다.

## WebFlux · Coroutines (이 레포의 핵심)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **WebFlux ↔ Coroutines boundary** | `feed-application` 의 use case 는 `suspend` / `Flow` 만 노출, Reactor 타입은 `feed-adapter-*` 안에서만 | [ADR-0001](adr/0001-webflux-coroutines-boundary.md) | `awaitSingle` / `asFlow` / `mono { }` / `asFlux` 변환을 adapter 경계에 가둠 — boundary 가 흐리면 cancellation leak |
| **functional `coRouter`** | [SseRouter](../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/sse/SseRouter.kt), [FeedRouter](../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/web/FeedRouter.kt) | ADR-0001 | annotation controller 대신 `coRouter { }` + `suspend` handler |
| **blocking 격리 (boundedElastic / Dispatchers.IO)** | catch-up 읽기 `.flowOn(Dispatchers.IO)` ([StreamFeedUseCase](../feed-application/src/main/kotlin/com/example/feed/application/usecase/StreamFeedUseCase.kt)) | [ADR-0002](adr/0002-bounded-elastic-isolation.md) | event-loop 스레드를 blocking 으로 막으면 전체 endpoint 지연 — `boundedElastic` / `Dispatchers.IO` 로 격리 |
| **structured concurrency + cancellation timeout** | [IngestTradeMatchedUseCase](../feed-application/src/main/kotlin/com/example/feed/application/usecase/IngestTradeMatchedUseCase.kt) (`coroutineScope` + `launch` + `runCatching`), [ComputeWindowStatsUseCase](../feed-application/src/main/kotlin/com/example/feed/application/usecase/ComputeWindowStatsUseCase.kt) (`withTimeout`) | [ADR-0010](adr/0010-coroutines-structured-concurrency.md) | `coroutineScope` vs `supervisorScope` 선택 + 부수효과 실패가 critical path 를 막지 않게 |
| **context propagation (Reactor Context ↔ CoroutineContext ↔ MDC)** | `Hooks.enableAutomaticContextPropagation()` ([RealtimeFeedApplication](../feed-bootstrap/src/main/kotlin/com/example/feed/RealtimeFeedApplication.kt)) | [ADR-0005](adr/0005-context-propagation.md) | traceId 가 router → use case → adapter 까지 로그에 일관되게 — Micrometer Observation 자동 전파 |
| **언제 reactive 를 쓰지 말아야 하나** | (이 레포 전체가 "써야 하는" 케이스) | [ADR-0008](adr/0008-when-not-to-use-reactive.md) | CPU bound / 단순 CRUD / JPA 종속 / 복잡한 트랜잭션 → imperative + virtual thread 가 낫다 |

→ 이론: `dev-lab/webflux` (논블로킹 event loop, Reactor Mono/Flux, coroutines bridge, backpressure)

## 스트리밍 · backpressure · fan-out

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **hot stream multicast** | [ReactorFeedSink](../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/sink/ReactorFeedSink.kt) — `Sinks.many().multicast().onBackpressureBuffer()`, SKU 별 `ConcurrentHashMap` | [ADR-0007](adr/0007-hot-stream-multicast.md) | 한 publisher(Kafka) → N subscriber(WS/SSE). `replay(N)` 대신 multicast + 별도 cache 로 메모리 일정 |
| **backpressure 정책 (sample / bufferTimeout / window)** | realtime 구간 `.sample(100ms)` ([StreamFeedUseCase](../feed-application/src/main/kotlin/com/example/feed/application/usecase/StreamFeedUseCase.kt)) | [ADR-0003](adr/0003-backpressure-strategy.md) | client-facing 은 `sample` (최신 상태만, 중간 drop 허용) — 호가/체결은 "현재 상태"가 중요 |
| **cancellation 전파 (disconnect → upstream)** | [FeedWebSocketHandler](../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/ws/FeedWebSocketHandler.kt) `session.send(outbound)`, `Flow.asFlux()` | [ADR-0006](adr/0006-cancellation-propagation.md) | WS 클라이언트 disconnect → subscription cancel 이 upstream 까지 전파 → subscription leak 방지 |
| **catch-up + realtime merge** | [StreamFeedUseCase](../feed-application/src/main/kotlin/com/example/feed/application/usecase/StreamFeedUseCase.kt) — cache 최근 N 건 `flow {}` + sink hot stream 을 `merge` | ADR-0003, [ADR-0007](adr/0007-hot-stream-multicast.md) | 구독 직후 화면이 비지 않도록 캐시 catch-up 을 realtime 앞에 이어붙임 |
| **부하로 정책 검증 (k6)** | `load/k6/scenarios/` — `websocket-fanout` / `backpressure` 등 5종, [load/README.md](../load/README.md) | ADR-0003 | `sample` drop 율 / fan-out / slow consumer 를 RPS 가 아니라 *정책 동작* 으로 관측 |

→ 이론: `dev-lab/webflux` (backpressure 전략, Reactor Sinks), `dev-lab/networking` (WebSocket vs SSE, long-lived connection 관리)

## 메시징 (Reactor Kafka)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Reactor Kafka consumer** | [TradeMatchedConsumer](../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/kafka/TradeMatchedConsumer.kt) — `KafkaReceiver.receive().asFlow()` | [ADR-0003](adr/0003-backpressure-strategy.md), [ADR-0001](adr/0001-webflux-coroutines-boundary.md) | Spring Kafka 와 달리 한 토픽 = 한 `Flux` → backpressure 를 stream 으로 자연스럽게 |
| **수동 commit + at-least-once** | TradeMatchedConsumer — `enable.auto.commit=false`, 처리 후 `receiverOffset().acknowledge()` | [ADR-0004](adr/0004-error-handling.md) | 처리 성공한 offset 만 ack. 실패 시 skip + log (DLQ 는 향후) |
| **consumer graceful shutdown** | TradeMatchedConsumer — `CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `@PreDestroy scope.cancel()` | [ADR-0010](adr/0010-coroutines-structured-concurrency.md) | 종료 시 자식 coroutine 정리, `SupervisorJob` 으로 한 자식 실패가 형제를 안 죽임 |
| **reactive error handling 매트릭스** | (consumer / use case 전반) | [ADR-0004](adr/0004-error-handling.md) | `onErrorResume` / `retryWhen` 은 쓰되 `onErrorContinue` 는 비결정성 때문에 금지 |

→ 이론: `dev-lab/kafka` (Reactor Kafka, consumer offset / 전달 의미), `dev-lab/resilience` (retry / backoff / DLQ)

## 영속 · 캐시 (R2DBC · Redis)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **R2DBC (non-blocking 영속)** | [FeedEventR2dbcAdapter](../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/r2dbc/FeedEventR2dbcAdapter.kt) — `DatabaseClient`, 단건 INSERT + 시간범위 SELECT | [ADR-0009](adr/0009-r2dbc-vs-jpa.md) | hot path 가 reactive 라 영속도 R2DBC 로 통일. JPA 의 caching/lazy/association 을 포기하는 대신 단순함 |
| **R2DBC vs JPA 의사결정** | — | [ADR-0009](adr/0009-r2dbc-vs-jpa.md), [ADR-0008](adr/0008-when-not-to-use-reactive.md) | 단순 INSERT/SELECT 면 R2DBC, association/복잡 트랜잭션이면 JPA — 판단 기준 |
| **Redis SortedSet catch-up 캐시** | [FeedRedisCacheAdapter](../feed-adapter-out/src/main/kotlin/com/example/feed/adapter/outbound/redis/FeedRedisCacheAdapter.kt) — `ZADD feed:recent:{sku} score=sequence`, Reactive Lettuce | [ADR-0007](adr/0007-hot-stream-multicast.md) | sink 의 메모리를 늘리는 대신 catch-up 책임을 Redis SortedSet 으로 분리 (sequence = score) |

→ 이론: `dev-lab/redis` (SortedSet, reactive Lettuce), `dev-lab/webflux` (R2DBC 와 event-loop)

## 보안 (Spring Security Reactive · JWT)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **OAuth2 Resource Server (JWT, reactive)** | [SecurityConfig](../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/security/SecurityConfig.kt) — `SecurityWebFilterChain`, `oauth2ResourceServer { jwt {} }`, `auth-service` JWK Set | — | reactive 체인에서 JWT 강제 검증. dev 프로필은 우회하되 endpoint 정의는 유지 (prod 와 차이 명시) |
| **profile 분기 보안 (dev 우회 / prod 강제)** | SecurityConfig — `@Profile("dev")` vs `@Profile("!dev")` 두 filter chain | — | 로컬 데모 편의 ↔ 운영 강제의 분리를 코드로 |
| **WebSocket/SSE Origin 화이트리스트** | [WebSocketOriginFilter](../feed-adapter-in/src/main/kotlin/com/example/feed/adapter/inbound/security/WebSocketOriginFilter.kt), `feed.security.allowed-origins` | — | handshake 시 Origin 검증 — 브라우저 cross-origin 구독 차단 |

→ 이론: `dev-lab/api-design` (JWT / OAuth2 Resource Server), `dev-lab/networking` (WebSocket handshake / Origin)

## 관측성 (Observability)

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Micrometer Observation → trace 전파** | `Hooks.enableAutomaticContextPropagation()` ([RealtimeFeedApplication](../feed-bootstrap/src/main/kotlin/com/example/feed/RealtimeFeedApplication.kt)) → `commerce-ops` Tempo | [ADR-0005](adr/0005-context-propagation.md) | reactive 에서 ThreadLocal 이 안 통하는 문제를 자동 context propagation 으로 해결 |
| **actuator + Prometheus** | `application.yml` `management.endpoints` (health/info/prometheus/metrics), k8s liveness/readiness group | — | sink subscriber 수 / drop 등 운영 지표 노출 지점 |
| **k6 → Prometheus remote-write 통합 대시보드** | [load/README.md](../load/README.md) 의 remote-write 절, `scripts/run-load.sh` | — | client 부하 metric 을 `commerce-ops` Prometheus 로 흘려 server actuator 와 한 화면에 |

→ 이론: `dev-lab/observability` (3축 metric/log/trace, trace context propagation), `dev-lab/networking` (RTT / tail latency)

## 아키텍처 (헥사고날)

| 패턴 | 이 레포 어디서 | 한 줄 |
|------|---------------|-------|
| **ports & adapters (5 모듈)** | `feed-domain ← feed-application ← feed-adapter-* ← feed-bootstrap` | 도메인이 Reactor 의존성 0 — port 인터페이스([FeedSink](../feed-application/src/main/kotlin/com/example/feed/application/port/FeedSink.kt) / [FeedCache](../feed-application/src/main/kotlin/com/example/feed/application/port/FeedCache.kt) / [FeedEventStore](../feed-application/src/main/kotlin/com/example/feed/application/port/FeedEventStore.kt)) 로 adapter 와 분리 |
| **value class 도메인** | [Money](../feed-domain/src/main/kotlin/com/example/feed/domain/Money.kt), [Ids](../feed-domain/src/main/kotlin/com/example/feed/domain/Ids.kt) (`SkuId` 등), [FeedWindow](../feed-domain/src/main/kotlin/com/example/feed/domain/FeedWindow.kt) (VWAP/spread 집계) | primitive obsession 회피 + 도메인 invariant 를 타입으로 |

→ 이론: `dev-lab/webflux` (reactive 아키텍처에서 layer 경계), `dev-lab/api-design` (port/adapter 계약)

## 학습 순서 제안 (이 레포 기준)

1. **[README](../README.md) 상단 + Quick Start** → `make up` / `make demo` 로 전체 흐름(호가→체결→fan-out) 감 잡기
2. **[ADR-0008](adr/0008-when-not-to-use-reactive.md)** → "언제 reactive 를 쓰지 말아야 하나" ← 이 레포의 출발점. 왜 이 도메인만 WebFlux 인지
3. **[ADR-0001](adr/0001-webflux-coroutines-boundary.md)** → WebFlux ↔ Coroutines boundary. 위 "WebFlux · Coroutines" 표의 코드와 함께
4. **스트리밍 표** → [ADR-0007](adr/0007-hot-stream-multicast.md)(multicast) → [ADR-0003](adr/0003-backpressure-strategy.md)(sample) → [ADR-0006](adr/0006-cancellation-propagation.md)(cancel). 이 레포의 심장
5. **나머지 ADR** ([0002](adr/0002-bounded-elastic-isolation.md) blocking 격리 / [0004](adr/0004-error-handling.md) error / [0005](adr/0005-context-propagation.md) context / [0009](adr/0009-r2dbc-vs-jpa.md) R2DBC / [0010](adr/0010-coroutines-structured-concurrency.md) structured concurrency)
6. **[load/README.md](../load/README.md)** → 위 정책이 부하에서 실제로 어떻게 동작하는지 (k6 5 시나리오)

> 짝 학습 레포: [dev-lab](https://github.com/ssa1004/dev-lab) (이론) ↔ 본 레포 (구현). 이론에서 "왜"를, 여기서 "실제로 어떻게"를 본다. 특히 `dev-lab/webflux` 가 본 레포와 가장 밀접하다.
