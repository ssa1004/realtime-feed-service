# ADR-0003: backpressure 전략 (bufferTimeout / sample / window)

## 상태
적용

## 배경
실시간 feed 의 특성: 한 SKU 에 수십~수백 명의 클라이언트가 붙어 있고, 일부는 모바일 망
지연으로 느리고 일부는 brower devtools 가 켜져 있어 느리다. 한 클라이언트의 느림이
다른 클라이언트나 upstream Kafka consumer 까지 멈추게 해서는 안 된다.

upstream 쪽 (Kafka consumer) 의 처리량은 초당 수천 건. 모든 이벤트를 모든 클라이언트가
실시간으로 받지 않아도 된다 — 호가 / 체결 도메인의 특성상 *최신 상태* 가 중요하지 *모든
스냅샷* 이 중요하지 않다. 이는 backpressure 정책 선택에 결정적이다.

## 결정

상황별로 세 가지 operator 를 나눠 쓴다.

### `sample(Duration)`
- **언제**: WebSocket / SSE 의 client-facing fan-out. 슬로우 클라이언트가 끊김 없이
  최신 상태를 받게 한다.
- **동작**: 윈도우 (예: 100ms) 마다 그 윈도우 안의 *마지막* 이벤트만 흘려보낸다. 중간
  값은 drop.
- **선택 이유**: 호가 / 체결 도메인은 100ms 안에 들어온 N 건의 호가 변화 중 마지막이
  사실상 가장 정확한 "현재 상태". 이전 N-1 건을 버려도 클라이언트의 화면이 이상해지지
  않는다.

본 레포의 적용:
```kotlin
sink.subscribe(skuId).sample(100L)   // StreamFeedUseCase
```

### `bufferTimeout(maxSize, maxTime)`
- **언제**: 영속 저장 / batch 처리. 작은 단위의 쓰기를 묶어 한 번에 처리해 throughput 향상.
- **동작**: 100건이 모이거나 100ms 가 지나면 List<T> 로 묶어 emit.
- **본 레포는 직접 적용하지 않음** — 본 repo 의 영속 저장은 단건 INSERT 가 R2DBC connection
  pool 안에서 충분히 빠르고, batch INSERT 도입 시 트랜잭션 단위가 복잡해진다. 트래픽이
  더 늘면 도입을 검토.

### `window(Duration)`
- **언제**: 시간 단위 통계 (volume / VWAP) 를 계산할 때.
- **동작**: 시간 윈도우마다 별도의 inner Flux 를 만들어 emit. inner 는 reduce / collect
  로 통계 산출.
- **본 레포의 적용**: `ComputeWindowStatsUseCase` 가 한 번의 쿼리로 처리하지만, 향후
  실시간 rolling window 가 필요하면 `Flux.window(Duration.ofMinutes(1))` + `flatMap { it.collectList() }`
  패턴을 적용한다.

### 선택 매트릭스

| 시나리오 | operator | 이유 |
|---|---|---|
| 실시간 push (client-facing) | `sample` | 최신 상태가 중요, 중간 drop 허용 |
| 영속/batch 저장 | `bufferTimeout` | throughput 향상 |
| 시간 윈도우 통계 | `window` | 윈도우 단위 aggregation |
| 모든 이벤트가 중요 (예: 결제) | `onBackpressureBuffer` (한도 X) | 절대 손실 금지 — 단 OOM 위험 |

## 결과
- slow consumer 가 다른 consumer 또는 upstream 을 막지 않는다.
- 도메인 특성에 맞는 손실 (sample) / 묶음 (bufferTimeout) 을 명시적으로 선택.
- (단점) `sample` 은 윈도우 안의 정확한 시퀀스를 잃는다 — 클라이언트가 sequence 를
  봤을 때 gap 이 생긴다. 이를 알리는 metadata 가 필요하면 별도 이벤트 (`SampleSkipped`) 를
  추가해야 한다 (현재 미도입).
- (단점) `bufferTimeout` 은 jitter 를 키운다 — 100ms 까지 기다리는 latency 가 추가.
  정밀 타이밍이 필요한 도메인에는 부적합.

## 다시 검토할 시점
- 클라이언트 측 sequence gap 이 UX 문제로 보고될 때 — `sample` → `conflate` 또는 명시적
  drop notification 으로 변경.
- 영속 저장 throughput 이 connection pool 한계에 닿을 때 — `bufferTimeout` 도입.
