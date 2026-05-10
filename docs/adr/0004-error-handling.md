# ADR-0004: error handling (onErrorResume / onErrorContinue / retryWhen)

## 상태
적용

## 배경
reactive stream 의 에러는 imperative 코드의 try/catch 와 다르다. 에러는 신호 (signal)
로 publisher 를 종료시키고, 한 번 종료된 publisher 는 재구독 (re-subscribe) 없이 다시
emit 하지 않는다. 이 특성 때문에 적절한 operator 선택이 시스템 안정성에 직결된다.

세 가지 핵심 operator 의 의미를 혼동하면 사이드 이펙트가 크다.

- `onErrorResume`: 에러를 잡아 *다른 publisher* 로 대체. fallback 시나리오.
- `onErrorReturn`: 에러를 잡아 *고정 값 1개* 로 대체. 단순 fallback.
- `onErrorContinue`: 에러를 *건너뛰고* 다음 element 로 진행. **위험 — operator 의 위치가
  중요하고 일부 operator (예: `flatMap`) 안에서는 동작이 직관적이지 않다**.
- `retryWhen`: 에러 시 재구독. backoff 정책 명시 가능.

## 결정

상황별 매트릭스:

### 1. 단일 요청 처리 (use case 안에서)
```kotlin
runCatching { externalCall() }
    .getOrElse { fallback() }
```
suspend 안에서는 그냥 try/catch 또는 `runCatching`. Reactor operator 동원 X.

### 2. 외부 호출 fallback (Reactor publisher)
```kotlin
externalMono.onErrorResume { ex ->
    log.warn("외부 호출 실패", ex)
    Mono.just(defaultValue)
}
```
`onErrorReturn` 은 너무 단순 — 로깅이 어렵다. `onErrorResume` 으로 명시적 처리.

### 3. 스트림 처리 중 일부 element 실패
**`onErrorContinue` 는 사용 금지**. 이유:
- `flatMap` 등 안에서는 캡처 못 함 (operator 가 onErrorContinue 를 *지원* 해야 함).
- 에러가 어느 element 에서 났는지 명시적으로 보이지 않아 디버깅 어려움.

대신 명시적으로:
```kotlin
flux.flatMap { element ->
    process(element).onErrorResume { ex ->
        log.warn("element {} 처리 실패: {}", element, ex.message)
        Mono.empty()  // 이 element 는 skip
    }
}
```

각 element 처리를 `onErrorResume` + `Mono.empty()` 로 감싸 *명시적으로 skip*. 위치가
분명하고 로깅 시점이 정확하다.

### 4. transient 에러 retry
```kotlin
mono.retryWhen(
    Retry.backoff(3, Duration.ofMillis(100))
        .maxBackoff(Duration.ofSeconds(2))
        .filter { it is IOException || it is TimeoutException }
)
```
모든 에러를 retry 하면 안 됨 — `filter` 로 transient 만. 또한 비-멱등 호출은 retry
금지. backoff 는 jitter 가 기본 포함 (Retry.backoff 가 자동).

### 5. terminal 에러 (publisher 자체 종료)
- Kafka consumer 의 publisher 가 죽으면 → 새 KafkaReceiver 를 만들어 재구독.
- 본 레포의 `TradeMatchedConsumer` 는 receive() 의 에러를 handle 안에서 잡지 않으면
  consumer 전체가 죽는다. 따라서 element 단위 try/catch 로 흡수 (현재 구현).

## 결과
- 에러가 어디서 어떻게 잡히는지 코드만 봐도 분명.
- `onErrorContinue` 의 비결정성을 회피.
- (단점) 모든 element 마다 try/catch 또는 `onErrorResume` 을 명시해야 해서 boilerplate.
  helper extension function 으로 일부 줄일 수 있다 (`Flow<T>.skipErrors(logger)` 등).
- (단점) Retry policy 가 코드에 흩어져 있으면 일관성이 떨어진다 — Resilience4j 와의
  중복도 검토 필요. 본 repo 는 retry 가 거의 필요 없는 경로뿐이라 단순 유지.

## 다시 검토할 시점
- transient 에러가 빈번하게 보고될 때 — 중앙화된 retry policy (Resilience4j 또는
  공통 helper) 도입 검토.
- 처리 실패가 누적되어 DLQ 패턴 (별도 topic) 이 필요해질 때.
