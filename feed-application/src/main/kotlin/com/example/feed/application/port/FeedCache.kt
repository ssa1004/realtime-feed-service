package com.example.feed.application.port

import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId

/**
 * Port — 최근 feed 이벤트 캐시. Redis adapter 가 구현. SortedSet 기반.
 *
 * 빠른 catch-up 을 위한 hot 영역. 진짜 영속은 [FeedEventStore].
 */
interface FeedCache {
    suspend fun cache(event: FeedEvent)
    suspend fun recent(skuId: SkuId, limit: Int): List<FeedEvent>
}
