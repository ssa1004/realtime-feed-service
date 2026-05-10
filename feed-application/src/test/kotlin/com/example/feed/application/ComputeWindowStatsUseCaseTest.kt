package com.example.feed.application

import com.example.feed.application.port.FeedEventStore
import com.example.feed.application.usecase.ComputeWindowStatsUseCase
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.Sequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ComputeWindowStatsUseCaseTest {

    private val sku = SkuId("NIKE-DUNK-X")
    private val fixedNow: Instant = Instant.parse("2026-05-09T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun trade(price: Long, qty: Int, atSec: Long) = FeedEvent.TradeMatched(
        skuId = sku,
        occurredAt = fixedNow.minusSeconds(atSec),
        sequence = Sequence(atSec),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(price),
        quantity = qty,
    )

    @Test
    fun `5분 윈도우 통계가 VWAP 와 함께 반환된다`() = runTest {
        val store = object : FeedEventStore {
            override suspend fun append(event: FeedEvent) {}
            override fun recent(skuId: SkuId, limit: Int): Flow<FeedEvent> = emptyFlow()
            override fun between(skuId: SkuId, from: Instant, to: Instant): Flow<FeedEvent> =
                listOf(
                    trade(100_000, 1, 60),  // 1분 전
                    trade(120_000, 9, 30),  // 30초 전
                ).asFlow()
        }
        val sut = ComputeWindowStatsUseCase(store, clock)

        val window = sut.computeRecent(sku, Duration.ofMinutes(5))

        assertThat(window.tradeCount).isEqualTo(2)
        assertThat(window.totalVolume).isEqualTo(10)
        // (100000 + 120000*9) / 10 = 118000
        assertThat(window.vwap).isEqualTo(Money(118_000))
        assertThat(window.highPrice).isEqualTo(Money(120_000))
        assertThat(window.lowPrice).isEqualTo(Money(100_000))
        assertThat(window.from).isEqualTo(fixedNow.minus(Duration.ofMinutes(5)))
        assertThat(window.to).isEqualTo(fixedNow)
    }
}
