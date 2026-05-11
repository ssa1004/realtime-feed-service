# ADR-0010: Coroutines structured concurrency + cancellation timeout 정책

## 상태
적용

## 배경
coroutine 의 핵심 가치는 *structured concurrency*: 자식 coroutine 의 lifecycle 이
부모의 lifecycle 에 묶여 있어 leak 가 발생하기 어렵다. 단 잘못 쓰면 의도와 다르게:

- 한 자식의 실패가 형제까지 cancel 시킨다 (CoroutineScope vs supervisorScope).
- 너무 오래 걸리는 자식이 부모의 응답을 무한 대기시킨다 (timeout 없음).
- 외부 호출의 cancel 신호가 cooperative cancellation point 가 없어 무시된다.

본 ADR 은 이 세 가지를 각각 어떻게 다룰지 정한다.

## 결정

### 1. 자식 실패 격리 — supervisorScope vs coroutineScope

| scope | 한 자식 실패 시 |
|---|---|
| `coroutineScope { ... }` | 다른 형제 모두 cancel + 부모로 전파 |
| `supervisorScope { ... }` | 실패한 자식만 cancel, 형제는 계속 |

**원칙**:
- 자식 실패가 의미적으로 *전체 실패* 인 경우 → `coroutineScope`. 예: 한 use case 안의
  모든 단계가 같이 가야 의미가 있을 때.
- 자식 각각이 *독립적인 부수효과* 일 때 → `supervisorScope`. 예: 본 repo 의
  `IngestTradeMatchedUseCase` 에서 cache 저장 / 영속 저장 / sink fan-out 은 서로 독립.

본 repo 의 `IngestTradeMatchedUseCase` 는 `coroutineScope` + 각 자식의 `runCatching`
패턴으로 의도적으로 단순화했다 (supervisorScope 와 동등한 효과를 명시적으로 표현).
이렇게 두면 structured concurrency 의 hierarchy 가 코드만 봐도 분명하게 드러난다.

### 2. timeout — withTimeout vs withTimeoutOrNull

```kotlin
withTimeout(5.seconds) { call() }       // 초과 시 TimeoutCancellationException
withTimeoutOrNull(5.seconds) { call() } // 초과 시 null 반환
```

**선택**:
- 외부 호출이 SLA 안에 끝나야 의미가 있을 때 → `withTimeout` + 호출자가 catch.
- timeout 도 정상 결과의 일부일 때 → `withTimeoutOrNull` + null 처리. 본 repo 의
  `ComputeWindowStatsUseCase` 는 `withTimeout` 을 쓰는데, 통계 응답이 5초를 넘으면
  사용자에게 의미가 없으므로 명시적 실패가 옳다.

### 3. cooperative cancellation

coroutine 은 *suspend point* 에서만 cancel 을 확인한다. 긴 CPU 루프 (예: 큰 컬렉션 fold)
는 중간에 `yield()` 를 넣지 않으면 cancel 이 무시된다.

본 repo 는 모든 hot path 가 suspend 함수 (Flow / DB / Redis / Kafka) 라 자동. CPU 루프
가 추가될 경우 `ensureActive()` 또는 `yield()` 를 의무적으로.

### 4. 본 repo 의 패턴 모음

#### "백그라운드 부수효과" 패턴

```kotlin
suspend fun handle(event) = coroutineScope {
    runCatching { critical(event) }.onFailure { log.error(it) }

    launch { runCatching { sideEffect1(event) } }
    launch { runCatching { sideEffect2(event) } }
}
```

부수효과의 실패가 critical path 를 막지 않게 launch + runCatching.

#### "병렬 fetch" 패턴

```kotlin
suspend fun loadEverything() = coroutineScope {
    val a = async { fetchA() }
    val b = async { fetchB() }
    Pair(a.await(), b.await())
}
```

둘 중 하나가 실패하면 둘 다 cancel — 의도된 동작.

#### "graceful shutdown" 패턴

```kotlin
class TradeMatchedConsumer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PreDestroy fun stop() { scope.cancel() }
}
```

application 종료 시 scope.cancel() 로 모든 자식이 정리. SupervisorJob 으로 한 자식의
실패가 형제를 죽이지 않게.

## 결과
- coroutine leak 가 거의 없음 — structured concurrency 의 의도대로.
- timeout 정책이 명시적 (`withTimeout` 의 timeout 값이 코드에 분명).
- (단점) `coroutineScope` vs `supervisorScope` 선택을 매번 의식해야 함. 잘못 고르면
  cascade cancel 또는 leak.
- (단점) `runCatching` 남용은 Result<T> 의 유실을 가져온다 — *반드시* `onFailure` 또는
  `getOrElse` 로 처리 (그냥 버리지 않기).

## 다시 검토할 시점
- coroutine leak 가 metric 으로 보고될 때 — `Job.invokeOnCompletion` 으로 추적 hook 을
  심거나, `CoroutineExceptionHandler` 로 미처리 예외 catch.
- timeout 으로 인한 사용자 불만이 늘 때 — `withTimeoutOrNull` + 부분 결과 정책으로 변경
  검토.
