package com.example.feed.application.usecase

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedEventStore
import com.example.feed.application.port.FeedSink
import com.example.feed.domain.FeedEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Use case — Kafka 에서 들어온 `trade.matched` 한 건을 처리한다.
 *
 * 1. 캐시 (Redis) 에 hot 데이터로 저장
 * 2. 영속 저장 (R2DBC)
 * 3. multicast sink 로 fan-out
 *
 * 1, 2, 3 의 순서는 의도적 — sink fan-out 이 가장 비싸고 slow consumer 영향이 가장 크다.
 * 캐시/저장 실패가 fan-out 을 막아서는 안 되므로 모두 별도 child coroutine 에서 실행하고,
 * 하나의 실패가 다른 두 개를 끊지 않도록 supervisorScope 가 아닌 coroutineScope + try/catch
 * 패턴을 쓴다 (ADR-0010).
 *
 * 본 use case 는 idempotent — 같은 sequence 의 이벤트가 중복으로 들어와도 sink 의 multicast 가
 * downstream 에 노출하는 양이 늘 뿐 데이터 일관성은 깨지지 않는다 (R2DBC 쪽 PK 충돌은 무시).
 */
@Service
class IngestTradeMatchedUseCase(
    private val sink: FeedSink,
    private val store: FeedEventStore,
    private val cache: FeedCache,
) {
    private val log = LoggerFactory.getLogger(IngestTradeMatchedUseCase::class.java)

    suspend fun handle(event: FeedEvent.TradeMatched) = coroutineScope {
        // hot path — sink 로 먼저 밀어 넣어야 latency 가 작다.
        runCatching { sink.emit(event) }
            .onFailure { log.warn("sink emit 실패 sku={}: {}", event.skuId.value, it.message) }

        // 백그라운드 — 캐시와 영속 저장은 fan-out latency 와 분리.
        launch {
            runCatching { cache.cache(event) }
                .onFailure { log.warn("cache 저장 실패 sku={}: {}", event.skuId.value, it.message) }
        }
        launch {
            runCatching { store.append(event) }
                .onFailure { log.warn("store append 실패 sku={}: {}", event.skuId.value, it.message) }
        }
    }
}
