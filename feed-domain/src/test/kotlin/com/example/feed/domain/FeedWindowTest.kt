package com.example.feed.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class FeedWindowTest {

    private val sku = SkuId("NIKE-DUNK-LOW-PANDA-270")
    private val now: Instant = Instant.parse("2026-05-09T00:00:00Z")

    private fun trade(price: Long, qty: Int, atSec: Long, seq: Long) = FeedEvent.TradeMatched(
        skuId = sku,
        occurredAt = now.plusSeconds(atSec),
        sequence = Sequence(seq),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(price),
        quantity = qty,
    )

    @Test
    fun `빈 윈도우는 vwap 와 high low 를 null 로 둔다`() {
        val w = FeedWindow.aggregate(sku, now, now.plusSeconds(60), emptyList())
        assertThat(w.tradeCount).isZero
        assertThat(w.totalVolume).isZero
        assertThat(w.vwap).isNull()
        assertThat(w.highPrice).isNull()
        assertThat(w.lowPrice).isNull()
    }

    @Test
    fun `VWAP 는 거래량 가중 평균이다`() {
        // 100원짜리 1개, 200원짜리 9개 → VWAP = (100 + 1800) / 10 = 190
        val trades = listOf(
            trade(price = 100, qty = 1, atSec = 0, seq = 1),
            trade(price = 200, qty = 9, atSec = 1, seq = 2),
        )
        val w = FeedWindow.aggregate(sku, now, now.plusSeconds(60), trades)

        assertThat(w.tradeCount).isEqualTo(2)
        assertThat(w.totalVolume).isEqualTo(10)
        assertThat(w.vwap).isEqualTo(Money(190))
        assertThat(w.highPrice).isEqualTo(Money(200))
        assertThat(w.lowPrice).isEqualTo(Money(100))
    }

    @Test
    fun `to 가 from 보다 이전이면 거부한다`() {
        runCatching {
            FeedWindow.aggregate(sku, now, now.minusSeconds(1), emptyList())
        }.onSuccess { error("거부되어야 한다") }
    }
}
