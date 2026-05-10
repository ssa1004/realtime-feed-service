# ADR-0001: WebFlux 와 Coroutines 의 boundary

## 상태
적용

## 배경
본 레포는 Spring WebFlux (Reactor 기반) 위에 100% Kotlin coroutines 로 application
layer 와 use case 를 작성한다. 두 비동기 모델이 한 코드베이스에 공존하면 다음 문제가
생긴다.

- 어디서는 `Mono<T>` 를 받고 어디서는 `suspend fun` 을 받는데, 변환이 산발적으로 일어나면
  실수로 blocking 호출이 끼어든다 (`mono.block()`).
- `Flow<T>` 와 `Flux<T>` 가 도메인 메서드 시그니처에 섞이면 호출자가 매번 어떤 쪽 추상화를
  쓸지 고민해야 한다.
- coroutine 의 cancellation 이 Reactor subscription cancel 로, 그 반대로도 자연스럽게
  전파되어야 (ADR-0006) 하는데 boundary 가 흐리면 leak 가 생긴다.

## 결정

다음 boundary 컨벤션을 강제한다.

### domain / application / use case 모듈
- 모든 함수 시그니처는 `suspend fun` 또는 `Flow<T>`. Reactor 타입 (`Mono<T>` / `Flux<T>`)
  을 노출하지 않는다.
- domain 모듈은 Reactor 의존성 자체가 없고, application 모듈은 stereotype 어노테이션과
  `kotlinx-coroutines-reactor` 만 허용 (`Mono` / `Flux` 를 import 가능하지만 시그니처에는
  쓰지 않는다).

### adapter-in (WebFlux)
- handler 시그니처는 `suspend fun handle(req: ServerRequest): ServerResponse`. `coRouter { }`
  DSL 을 쓴다.
- WebSocket handler 는 Reactor 가 요구하는 `Mono<Void>` 를 반환하지만, 내부는 `Flow` →
  `asFlux()` 로만 변환.
- 응답이 stream 이면 `bodyAndAwait(flow)` 또는 `bodyToServerSentEvents(flow.asFlux())`.

### adapter-out (Kafka / R2DBC / Redis)
- Reactor 라이브러리가 직접 노출하는 `Mono` / `Flux` 는 adapter 내부에서 즉시
  `awaitSingle` / `awaitSingleOrNull` 또는 `asFlow()` 로 변환해 port 인터페이스에 맞춘다.
- Reactor Kafka 의 `KafkaReceiver.receive(): Flux<ReceiverRecord>` 는 `asFlow()` 로
  변환 후 collect 한다.

### 변환 규칙 요약

| 방향 | 방법 |
|---|---|
| `Mono<T>` → `suspend T` | `awaitSingle()` (값 보장) / `awaitSingleOrNull()` (null 가능) |
| `Flux<T>` → `Flow<T>` | `asFlow()` |
| `suspend T` → `Mono<T>` | `mono { ... }` (kotlinx-coroutines-reactor) |
| `Flow<T>` → `Flux<T>` | `flow.asFlux()` |

## 결과
- 도메인 / application / use case 가 Reactor 에 의존하지 않아 다른 reactive runtime
  (RxJava 등) 으로 갈아끼울 수 있다 (실제로 갈아끼울 일은 거의 없지만 추상화가 명확).
- 코드 리뷰 시 시그니처만 봐도 어느 layer 인지 분명. `Mono` 가 application 모듈에 보이면
  즉시 위반 신호.
- (단점) 변환 비용 — `mono { }` / `awaitSingle` 가 매 호출마다 wrapping. CPU profiling
  결과 hot path 에서 의미있게 보이지 않으나, 초고성능 path 에는 검토 필요.
- (단점) 신규 인원이 두 모델을 모두 알아야. 컨벤션을 README + 본 ADR + 모듈별 build.gradle.kts
  의 의존성 주석으로 보강.

## 다시 검토할 시점
- Reactor 가 Kotlin coroutines 와 더 매끄럽게 통합되는 새 버전이 나왔을 때 (예: `await*`
  의 deprecation).
- WebFlux 가 Loom 기반 virtual threads 로 일부 path 를 대체하기 시작했을 때 — 그러면
  단순 endpoint 는 blocking + virtual thread 가 더 단순할 수 있다 (ADR-0008 과 함께 봄).
