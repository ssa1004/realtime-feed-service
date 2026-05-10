package com.example.feed.application.port

import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Port — feed 이벤트의 영속 저장소. R2DBC adapter 가 이를 구현한다.
 *
 * suspend / Flow 만 노출 — Reactor 타입은 adapter 구현 안에서만 쓰고 경계에서 변환 (ADR-0001).
 */
interface FeedEventStore {
    suspend fun append(event: FeedEvent)

    /**
     * 최근 N 건. 클라이언트가 막 구독을 시작했을 때 따라잡기 (catch-up) 용으로 쓴다.
     */
    fun recent(skuId: SkuId, limit: Int): Flow<FeedEvent>

    /**
     * 시간 범위 조회. windowing 통계 계산에 사용.
     */
    fun between(skuId: SkuId, from: Instant, to: Instant): Flow<FeedEvent>
}
