package com.example.feed.application.usecase

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedSink
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Use case — 한 클라이언트의 feed 구독.
 *
 * 1. catch-up: cache 에서 최근 N 건을 먼저 흘려보낸다 (구독 시작 직후 화면이 비지 않도록).
 * 2. realtime: sink 에서 hot stream 을 받는다.
 *
 * backpressure: realtime 구간에 [sample] 적용 — 클라이언트가 느리면 sampleWindow 마다
 * 가장 최근 값만 흘려보낸다 (ADR-0003 의 "느린 client 자동 drop"). 호가/체결 feed 의 특성상
 * 모든 이벤트를 보내는 것보다 가장 최신 상태를 빠르게 보여주는 것이 더 중요하다.
 *
 * catch-up 과 realtime 사이에 race 가 있을 수 있다 (catch-up 동안 realtime 이 새 이벤트를
 * 발행). 이 정도는 sequence 비교로 클라이언트가 흡수하면 충분하다 — 본 use case 는 단순히
 * 두 flow 를 merge 한다.
 */
@Service
class StreamFeedUseCase(
    private val sink: FeedSink,
    private val cache: FeedCache,
) {
    private val log = LoggerFactory.getLogger(StreamFeedUseCase::class.java)

    fun stream(
        skuId: SkuId,
        catchUpLimit: Int = 50,
        sampleWindowMs: Long = 100L,
    ): Flow<FeedEvent> {
        val catchUp: Flow<FeedEvent> = flow {
            cache.recent(skuId, catchUpLimit).forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

        val realtime: Flow<FeedEvent> = sink.subscribe(skuId)
            .sample(sampleWindowMs)

        return merge(catchUp, realtime)
            .onStart { log.debug("feed stream 시작 sku={}", skuId.value) }
            .onCompletion { cause ->
                log.debug("feed stream 종료 sku={} cause={}", skuId.value, cause?.message ?: "정상")
            }
    }
}
