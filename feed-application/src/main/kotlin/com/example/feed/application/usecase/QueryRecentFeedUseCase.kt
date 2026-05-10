package com.example.feed.application.usecase

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedEventStore
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

/**
 * Use case — 최근 feed N 건 조회 (REST GET, WebSocket 구독 직후 catch-up 등).
 *
 * 우선 cache 에서 시도, 부족하면 store 에서 보충. 둘 다 동기적으로 (suspend) 실행하여 응답
 * latency 를 단순화. 캐시 hit ratio 가 보통 높으므로 store 호출은 cold path 에 가깝다.
 */
@Service
class QueryRecentFeedUseCase(
    private val cache: FeedCache,
    private val store: FeedEventStore,
) {
    suspend fun query(skuId: SkuId, limit: Int): Flow<FeedEvent> {
        require(limit in 1..1000) { "limit 은 1~1000 범위" }
        val cached = cache.recent(skuId, limit)
        return if (cached.size >= limit) {
            cached.asFlow()
        } else {
            // 캐시가 부족하면 store 에서 limit 만큼 다시. 단순화 — 운영에선 cache 부족분만 보충.
            store.recent(skuId, limit).toList().asFlow()
        }
    }
}
