package com.example.feed.application.port

import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.Flow

/**
 * Port — hot stream multicast. 한 publisher (Kafka consumer) 가 [emit] 으로 밀어 넣고,
 * 여러 subscriber (WebSocket / SSE) 가 [subscribe] 로 받는다.
 *
 * 구현 (FeedSinkAdapter) 은 Reactor `Sinks.many().multicast().onBackpressureBuffer()` 를 쓴다.
 * ADR-0007 — slow consumer 처리, ADR-0003 — backpressure 정책 참고.
 */
interface FeedSink {
    suspend fun emit(event: FeedEvent)

    /**
     * SKU 별 hot stream 구독. 받는 즉시 실시간으로 전달된다 (catch-up 은 [FeedCache] 가 별도 처리).
     *
     * 반환된 Flow 는 cold (각 호출이 새 구독 생성). 캔슬되면 upstream 구독도 해제된다.
     */
    fun subscribe(skuId: SkuId): Flow<FeedEvent>
}
