package com.example.feed.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * 시간 윈도우 통계 — 최근 N 분간의 거래를 모은 결과.
 *
 * VWAP (volume-weighted average price) = Σ(price × qty) / Σ(qty).
 * 일반 평균과 달리 큰 거래의 가격이 더 큰 가중치를 가진다.
 */
data class FeedWindow(
    val skuId: SkuId,
    val from: Instant,
    val to: Instant,
    val tradeCount: Int,
    val totalVolume: Long,
    val vwap: Money?,
    val highPrice: Money?,
    val lowPrice: Money?,
) {
    val duration: Duration = Duration.between(from, to)

    init {
        require(!to.isBefore(from)) { "to 가 from 보다 이전일 수 없다" }
        require(tradeCount >= 0) { "체결 수는 0 이상" }
        require(totalVolume >= 0) { "거래량은 0 이상" }
    }

    companion object {
        /**
         * 체결 이벤트들을 모아 윈도우 통계를 만든다.
         * 빈 컬렉션이 들어오면 vwap / high / low 는 null.
         */
        fun aggregate(
            skuId: SkuId,
            from: Instant,
            to: Instant,
            trades: Collection<FeedEvent.TradeMatched>,
        ): FeedWindow {
            if (trades.isEmpty()) {
                return FeedWindow(
                    skuId = skuId,
                    from = from,
                    to = to,
                    tradeCount = 0,
                    totalVolume = 0,
                    vwap = null,
                    highPrice = null,
                    lowPrice = null,
                )
            }

            var sumPriceQty = BigDecimal.ZERO
            var sumQty = 0L
            var hi: Money = trades.first().price
            var lo: Money = trades.first().price

            trades.forEach { t ->
                sumPriceQty = sumPriceQty.add(t.price.toBigDecimal().multiply(BigDecimal.valueOf(t.quantity.toLong())))
                sumQty += t.quantity
                if (t.price > hi) hi = t.price
                if (t.price < lo) lo = t.price
            }

            val vwap = if (sumQty == 0L) null else
                Money.fromBigDecimal(sumPriceQty.divide(BigDecimal.valueOf(sumQty), 8, RoundingMode.HALF_UP))

            return FeedWindow(
                skuId = skuId,
                from = from,
                to = to,
                tradeCount = trades.size,
                totalVolume = sumQty,
                vwap = vwap,
                highPrice = hi,
                lowPrice = lo,
            )
        }
    }
}
