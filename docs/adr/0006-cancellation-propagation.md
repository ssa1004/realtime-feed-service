# ADR-0006: cancellation propagation (WebSocket disconnect → upstream)

## 상태
적용

## 배경
WebSocket / SSE 클라이언트는 자유롭게 연결을 끊는다 — 브라우저 탭 닫기, 앱 백그라운드
진입, 모바일 망 끊김 등. 클라이언트가 끊기면 서버 측에서:

1. WebSocket session 이 close
2. 그 session 의 send Mono 가 종료 (정상 또는 에러)
3. 해당 client 를 위한 hot stream subscription 도 자동으로 cancel
4. cancel 이 upstream 의 multicast sink 로 전파되어 subscriber count 감소
5. 마지막 subscriber 가 끊기면 sink 자체가 quiet 해짐 (단, 본 repo 의 `multicast()` 는
   refCount 동작이 아니라 항상 살아있음 — 별도 GC 필요)

이 전파가 깨지면:
- 서버 메모리에 더 이상 받지 않는 subscription 이 남아 leak
- `Sinks.many().multicast()` 의 buffer 가 무한히 증가
- Kafka consumer 가 쓸모없는 work 를 계속

## 결정

### Reactor 측 — subscription cancellation 의 자동 전파를 신뢰

Reactor 의 publisher chain 은 downstream cancel 이 upstream 까지 자동 전파된다
(`Disposable.dispose()`). 본 repo 의 `FeedWebSocketHandler` 는:

```kotlin
override fun handle(session: WebSocketSession): Mono<Void> {
    val outbound = streamUseCase.stream(skuId).asFlux()
    return session.send(outbound)  // session 종료 시 outbound 도 자동 cancel
}
```

`session.send(outbound)` 가 반환하는 `Mono<Void>` 는 session close 시 종료. Reactor 가
upstream `outbound` (Flux) 에 cancel 신호를 보낸다.

### Coroutines 측 — Job hierarchy 로 자동 전파

`Flow.collect` 안에서 [CancellationException](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-cancellation-exception/) 이
발생하면 collect 가 종료된다. 본 repo 는:

```kotlin
sink.subscribe(skuId)
    .sample(100L)
    .onCompletion { cause -> log.debug("종료 cause={}", cause?.message) }
```

`Flow.asFlux().subscribe()` → cancel → underlying Flow 의 collect 가 cancel.

### asFlow / asFlux 의 cancellation 보장

`kotlinx-coroutines-reactor` 의 변환 함수들은 cancellation 양방향 전파를 보장:

| 변환 | cancellation 방향 |
|---|---|
| `Flux.asFlow()` | Flow collect 캔슬 → Flux subscription dispose |
| `Flow.asFlux()` | Flux subscription dispose → Flow collect 캔슬 |
| `mono { suspendCall() }` | Mono cancel → suspendCall 캔슬 |

**boundary 위반이 cancellation leak 의 주범**. ADR-0001 을 지키면 leak 위험이 크게 줄어든다.

### Sink 측 GC

`Sinks.many().multicast()` 의 subscriber 가 모두 끊겨도 sink 자체는 살아있음. 본 repo 는
`ConcurrentHashMap<String, Sinks.Many<>>` 로 SKU 별 sink 를 lazy 생성한다. 운영에서 SKU
카탈로그가 무한히 커지지 않도록 (대략 만 단위 가정), 추가 GC 로직은 없다. 만약 SKU 가
폭증하면 별도 백그라운드 잡으로:

```kotlin
sinks.entries.removeIf { it.value.currentSubscriberCount() == 0 && idleFor(it.key) > 1.hour }
```

## 결과
- 클라이언트 disconnect 가 즉시 upstream 까지 전파 → leak 없음.
- Reactor + Coroutines 의 cancellation 의미가 통일.
- (단점) sink GC 가 자동이 아님. 본 repo 는 SKU 카탈로그 크기 가정으로 회피.
- (단점) `Sinks.many().multicast()` 가 마지막 subscriber 종료 시 cleanup 하지 않는 동작은
  `multicast().autoCancel(true)` 로 바꿀 수도 있는데, 그러면 새 subscriber 가 들어왔을 때
  sink 가 새로 만들어져 buffer 가 날아간다 (catch-up 깨짐). 본 repo 는 별도 cache layer
  ([FeedCache](../../feed-application/src/main/kotlin/com/example/feed/application/port/FeedCache.kt))
  가 catch-up 을 담당하므로 autoCancel 도 가능 — trade-off.

## 다시 검토할 시점
- SKU 카탈로그가 백만 단위로 늘어 sink 메모리가 의미있게 보일 때 — sink GC 잡 도입.
- `multicast().autoCancel(true)` 로 단순화 가능한지 catch-up 동작과 함께 재검증.
