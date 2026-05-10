# ADR-0007: hot stream multicast — Sinks.many().multicast() trade-off

## 상태
적용

## 배경
한 SKU 의 체결 / 호가 이벤트는 한 publisher (Kafka consumer) 가 발행하고 N 명의 subscriber
(WebSocket / SSE 클라이언트) 가 받아야 한다. cold publisher 를 그대로 쓰면 매 subscriber
마다 새 데이터 흐름이 만들어져 — Kafka consume 이 N 번 일어나는 셈. 잘못된 모델.

Reactor 의 `Sinks.many()` 는 hot stream multicast 를 위한 표준 도구. 그 안에 다시 여러
flavor 가 있다.

| Sink flavor | 동작 |
|---|---|
| `multicast()` | subscriber 가 0 → N 으로 늘어도 emit 손실 없이 받기 시작. 이전 emit 은 못 봄 |
| `replay(N)` | 마지막 N 개를 buffer 에 저장, 새 subscriber 가 처음부터 받음 |
| `replay().latestOrDefault(x)` | 마지막 1 개만 즉시 보내고 그 이후 실시간 |
| `unicast()` | 한 subscriber 만 (1:1) |
| `multicast().onBackpressureBuffer(N)` | multicast + slow consumer 용 buffer (N 한도) |

선택은 도메인의 catch-up 정책과 메모리 점유 trade-off.

## 결정

`Sinks.many().multicast().onBackpressureBuffer<FeedEvent>(BUFFER_SIZE, false)` 를 쓴다.
SKU 별로 별도 sink (ConcurrentHashMap).

### 이유

1. **multicast** — 새 subscriber 가 들어와도 이전 emit 은 못 봐도 된다. 도메인의
   catch-up 은 별도 [FeedCache](../../feed-application/src/main/kotlin/com/example/feed/application/port/FeedCache.kt)
   (Redis SortedSet) 가 담당. 이 분리로 sink 의 메모리 점유는 buffer 한도만큼만.

2. **replay(N) 와의 비교** — replay 를 쓰면 sink 가 catch-up 도 겸할 수 있어 단순.
   하지만:
   - SKU 가 만 개일 때 N=200 → 200만 개의 이벤트가 메모리에 상주 (이벤트당 ~200B = 400MB).
   - replay 의 buffer 는 GC 될 때까지 남아 메모리 압박.
   - JVM heap 의 long-lived object 가 늘어 GC pause 가 길어짐.
   - 캐시 layer (Redis) 가 이미 있으면 sink 의 replay 는 중복.

3. **onBackpressureBuffer(N, false)** — N=1024 (현재 설정). slow consumer 가 따라오지
   못하면 buffer 가 차고 `EmitResult.FAIL_OVERFLOW` 로 떨어진다. publisher 측
   (ReactorFeedSink.emit) 은 결과를 검사하고 metric 을 올린다. publisher 자체가 멈추지
   않게 `false` (autoCancel 비활성화).

4. **SKU 별 분리** — 한 SKU 의 slow consumer 가 다른 SKU 의 fan-out 에 영향 없음. 단점은
   sink 객체 수 = SKU 수 (만 개라면 만 개 sink). sink 한 개 ~수백 byte 라 메모리 영향 X.

### Slow consumer 처리 정책

```
1. publisher 가 emit → multicast → subscriber 마다 buffer
2. subscriber 의 buffer 가 BUFFER_SIZE 도달
3. publisher 의 emit 이 OVERFLOW → 해당 emit 만 손실 (다른 subscriber 는 OK)
4. 손실 횟수가 임계치를 넘으면 client 측에 disconnect 통보 (현재 미구현 — 향후)
```

### currentSubscriberCount() 활용

운영에서 metric 으로 노출:
```
feed_sink_subscriber_count{sku="..."} → SKU 별 활성 client 수
feed_sink_active_sku_count → 활성 SKU 수
```

## 결과
- 메모리 점유 예측 가능 — `(sku 수) × (buffer 크기 × 이벤트 평균 크기)`.
- catch-up 과 fan-out 책임 분리 → 각각 단순.
- SKU 단위 격리.
- (단점) sink 의 GC 자동 아님 (ADR-0006). 운영에서 SKU 폭증 시 별도 cleanup.
- (단점) catch-up 과 realtime 사이에 race 가 있을 수 있음 — catch-up 동안 realtime
  새 이벤트가 도착하면 시퀀스가 뒤섞임. 클라이언트가 sequence 비교로 흡수하거나 dedup 필요.

## 다시 검토할 시점
- catch-up 의 race 가 클라이언트 UX 문제로 보고될 때 — `replay(N)` 로 일원화 검토 (메모리
  trade-off 다시).
- SKU 가 백만 단위로 늘 때 — sink GC 자동화 또는 sharding (다중 instance 의 SKU 분할).
- `Sinks.many().unicast()` 로 client 별 buffering 격리가 필요해질 때 (현재는 multicast
  의 fan-out 으로 충분).
