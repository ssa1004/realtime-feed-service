# ADR-0002: blocking call 의 boundedElastic 격리

## 상태
적용

## 배경
WebFlux 는 단일 event-loop scheduler (`Schedulers.parallel()`) 위에 endpoint 를 돌린다.
event-loop 스레드는 CPU 코어 수만큼 (보통 2~16개) 만 있어서 한 스레드가 blocking I/O 로
잠시라도 멈추면 그 동안 모든 endpoint 의 응답이 지연된다. 일반적인 Spring MVC 는
스레드를 풍부하게 잡고 (200개) 기다리는 게 정상이지만, WebFlux 에서는 절대 안 된다.

본 레포는 100% reactive 를 지향하지만, 외부 의존이 reactive client 를 제공하지 않는
경우가 종종 있다 — 예: 일부 metrics / tracing 라이브러리, 레거시 SDK. 이때 어느
scheduler 로 격리할지 정해야 한다.

## 결정

blocking call 은 `Schedulers.boundedElastic()` 으로 격리한다.

### 왜 boundedElastic?

- **bounded**: 스레드 수 한도 (`10 × CPU`, 기본 약 200) 가 있다 → 폭주해도 JVM 이 OOM
  되거나 스레드 수가 폭증하지 않는다.
- **elastic**: 필요할 때 늘었다가 idle 하면 줄어든다 (60초 idle 기본).
- **caller-runs 거부**: bounded 한도 초과 시 `RejectedExecutionException` 으로 즉시
  실패 → caller 가 cb / fallback 으로 처리할 수 있다.

### parallel 과의 차이

| 측면 | `Schedulers.parallel()` | `Schedulers.boundedElastic()` |
|---|---|---|
| 스레드 수 | CPU 코어 수 (고정) | 동적 (max ~10 × CPU) |
| 용도 | 빠른 CPU bound 작업 | blocking I/O 격리 |
| daemon | yes | yes |
| keep-alive | n/a | 60초 idle 후 회수 |

`parallel` 은 CPU bound 의 경우만 안전. blocking 호출이 들어가면 코어 수만큼만 동시 처리
되어 처리량이 죽는다. blocking 은 무조건 `boundedElastic`.

### 사용 예시

```kotlin
// Reactor 쪽
mono { someBlockingClient.call() }
    .subscribeOn(Schedulers.boundedElastic())

// Coroutines 쪽 — Dispatchers.IO 가 동등 역할
withContext(Dispatchers.IO) {
    someBlockingClient.call()
}
```

`Dispatchers.IO` 는 coroutine 측의 boundedElastic — 같은 의도. 본 레포는 코드 위치에
따라 둘을 골라 쓴다 (adapter-in 의 router 안에서 작은 blocking 호출은 `Dispatchers.IO`,
adapter 내부에서 Reactor publisher 를 받을 때는 `subscribeOn(boundedElastic)`).

## 결과
- blocking 호출이 event-loop 를 막지 않는다 → 다른 endpoint 의 latency 영향 0.
- 한도가 있어 폭주해도 안전.
- (단점) blocking 호출이 늘면 boundedElastic 스레드 수가 부풀어 메모리 오버헤드 (스레드당
  ~512KB stack). 200 스레드 = 100MB. 진짜 많이 쓰면 reactive client 로 바꾸는 편이 옳다.
- (단점) `subscribeOn` 위치를 헷갈리면 효과가 없다 — chain 의 위쪽 publisher 만 영향.
  publisher 자체가 blocking 인 경우 `subscribeOn` 으로 격리되지만, downstream operator 가
  blocking 이면 `publishOn` 이 필요 (둘의 차이는 별도 학습).

## 다시 검토할 시점
- blocking 의존 (legacy SDK) 이 reactive client 를 제공하기 시작했을 때 — 즉시 마이그.
- JVM 의 virtual thread (Loom) 가 production-ready 가 되어 reactive 자체를 재검토할 때
  (ADR-0008 과 함께).
