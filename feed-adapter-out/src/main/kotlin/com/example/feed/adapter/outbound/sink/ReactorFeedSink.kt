package com.example.feed.adapter.outbound.sink

import com.example.feed.application.port.FeedSink
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * [FeedSink] 의 Reactor 구현 — `Sinks.many().multicast().onBackpressureBuffer()` 기반.
 *
 * 한 SKU 마다 별도 sink 를 둔다. 같은 sink 에 모든 SKU 가 섞이면:
 *   - 한 SKU 의 slow consumer 가 다른 SKU 까지 영향
 *   - subscribe 측에서 매번 filter 가 돌아 CPU 낭비
 *
 * SKU 별 sink 는 ConcurrentHashMap 으로 lazily 생성. SKU 가 비활성화되면 자동 회수되지는
 * 않지만 SKU 카탈로그 크기가 제한적 (~만 단위) 이라 실용상 무시 가능. 진짜 운영이라면
 * `currentSubscriberCount() == 0` 인 sink 를 GC 하는 백그라운드 잡 필요.
 *
 * `multicast` 선택 이유 — `replay(N)` 와 비교해 메모리 점유가 일정하다. catch-up 은 별도
 * 캐시 ([com.example.feed.application.port.FeedCache]) 가 담당하므로 sink 는 hot path 만
 * 책임진다. 자세한 trade-off 는 ADR-0007.
 *
 * `onBackpressureBuffer` — 가장 단순한 backpressure 정책. 버퍼 크기 [BUFFER_SIZE] 초과 시
 * `OverflowStrategy.ERROR` (기본) 로 publisher 가 실패한다. 운영에서는 metric 을 봐가며
 * tune 한다.
 */
@Component
class ReactorFeedSink : FeedSink {

    private val log = LoggerFactory.getLogger(ReactorFeedSink::class.java)
    private val sinks = ConcurrentHashMap<String, Sinks.Many<FeedEvent>>()

    override suspend fun emit(event: FeedEvent) {
        val sink = sinkFor(event.skuId)
        when (val result = sink.tryEmitNext(event)) {
            Sinks.EmitResult.OK -> Unit
            else -> log.warn("sink emit 실패 sku={} result={}", event.skuId.value, result)
        }
    }

    override fun subscribe(skuId: SkuId): Flow<FeedEvent> {
        // sink.asFlux() 는 cold — 매 호출이 새로운 subscription 을 만든다.
        return sinkFor(skuId).asFlux()
            .asFlow()
            .filter { it.skuId == skuId }   // 안전망 — sinkFor 가 이미 SKU 단위지만 한 번 더.
    }

    private fun sinkFor(skuId: SkuId): Sinks.Many<FeedEvent> =
        sinks.computeIfAbsent(skuId.value) {
            Sinks.many().multicast().onBackpressureBuffer<FeedEvent>(BUFFER_SIZE, false)
        }

    fun activeSkuCount(): Int = sinks.size

    companion object {
        /** SKU 당 in-flight 버퍼. 100ms × 1000 events/sec = 100 — 100 의 10 배 여유. */
        const val BUFFER_SIZE = 1024
    }
}
