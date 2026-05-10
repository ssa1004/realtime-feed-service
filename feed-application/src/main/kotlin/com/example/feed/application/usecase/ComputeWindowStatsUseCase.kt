package com.example.feed.application.usecase

import com.example.feed.application.port.FeedEventStore
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.FeedWindow
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Use case — 최근 N 분 윈도우 통계 (volume / VWAP / high / low).
 *
 * 본 use case 는 도메인 [FeedWindow.aggregate] 를 호출만 한다. R2DBC 쿼리는
 * suspend store.between() 으로 받아서 toList() 로 모은다 (윈도우 결과는 한 객체이므로 streaming 무의미).
 *
 * SLA: 5초 안에 응답 — withTimeout 으로 강제. 초과 시 [kotlinx.coroutines.TimeoutCancellationException].
 */
@Service
class ComputeWindowStatsUseCase(
    private val store: FeedEventStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun computeRecent(skuId: SkuId, window: Duration, timeoutMs: Long = 5_000L): FeedWindow {
        require(!window.isNegative && !window.isZero) { "window 는 양의 Duration" }
        val to: Instant = clock.instant()
        val from: Instant = to.minus(window)

        val trades: List<FeedEvent.TradeMatched> = withTimeout(timeoutMs) {
            store.between(skuId, from, to)
                .filterIsInstance<FeedEvent.TradeMatched>()
                .toList()
        }
        return FeedWindow.aggregate(skuId, from, to, trades)
    }
}
