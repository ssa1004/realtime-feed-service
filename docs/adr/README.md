# Architecture Decision Records

본 레포의 설계 결정을 기록한 문서들입니다. 각 ADR 은 동일한 형식을 따릅니다.

- **배경**: 왜 이 결정이 필요한가
- **결정**: 무엇을 골랐는가
- **결과**: 장단점과 다시 검토할 시점

| 번호 | 제목 |
|---|---|
| [ADR-0001](0001-webflux-coroutines-boundary.md) | WebFlux 와 Coroutines 의 boundary |
| [ADR-0002](0002-bounded-elastic-isolation.md) | blocking call 의 boundedElastic 격리 |
| [ADR-0003](0003-backpressure-strategy.md) | backpressure 전략 (bufferTimeout / sample / window) |
| [ADR-0004](0004-error-handling.md) | error handling (onErrorResume / onErrorContinue / retryWhen) |
| [ADR-0005](0005-context-propagation.md) | context propagation (Reactor Context / CoroutineContext / MDC) |
| [ADR-0006](0006-cancellation-propagation.md) | cancellation propagation (WebSocket disconnect → upstream) |
| [ADR-0007](0007-hot-stream-multicast.md) | hot stream multicast — Sinks.many().multicast() trade-off |
| [ADR-0008](0008-when-not-to-use-reactive.md) | 언제 reactive 를 쓰지 말아야 하나 |
| [ADR-0009](0009-r2dbc-vs-jpa.md) | R2DBC vs JPA |
| [ADR-0010](0010-coroutines-structured-concurrency.md) | Coroutines structured concurrency + cancellation timeout |
