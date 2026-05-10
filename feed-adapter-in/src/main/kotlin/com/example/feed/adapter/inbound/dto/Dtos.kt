package com.example.feed.adapter.inbound.dto

import com.example.feed.domain.FeedEvent
import com.example.feed.domain.FeedWindow
import java.time.Instant

/**
 * 외부 노출 DTO. 도메인 타입을 그대로 노출하지 않는다 — sealed class 의 직렬화는 어렵고,
 * 무엇보다 외부 계약과 도메인 변경 주기를 분리한다.
 */
data class FeedEventDto(
    val type: String,
    val skuId: String,
    val occurredAt: Instant,
    val sequence: Long,
    val tradeId: String? = null,
    val priceKrw: Long? = null,
    val quantity: Int? = null,
) {
    companion object {
        fun from(event: FeedEvent): FeedEventDto = when (event) {
            is FeedEvent.TradeMatched -> FeedEventDto(
                type = "TRADE_MATCHED",
                skuId = event.skuId.value,
                occurredAt = event.occurredAt,
                sequence = event.sequence.value,
                tradeId = event.tradeId.value.toString(),
                priceKrw = event.price.krw,
                quantity = event.quantity,
            )
            is FeedEvent.BidAskUpdated -> FeedEventDto(
                type = "BID_ASK_UPDATED",
                skuId = event.skuId.value,
                occurredAt = event.occurredAt,
                sequence = event.sequence.value,
            )
            is FeedEvent.Heartbeat -> FeedEventDto(
                type = "HEARTBEAT",
                skuId = event.skuId.value,
                occurredAt = event.occurredAt,
                sequence = event.sequence.value,
            )
        }
    }
}

data class WindowStatsDto(
    val skuId: String,
    val from: Instant,
    val to: Instant,
    val tradeCount: Int,
    val totalVolume: Long,
    val vwapKrw: Long?,
    val highPriceKrw: Long?,
    val lowPriceKrw: Long?,
) {
    companion object {
        fun from(window: FeedWindow): WindowStatsDto = WindowStatsDto(
            skuId = window.skuId.value,
            from = window.from,
            to = window.to,
            tradeCount = window.tradeCount,
            totalVolume = window.totalVolume,
            vwapKrw = window.vwap?.krw,
            highPriceKrw = window.highPrice?.krw,
            lowPriceKrw = window.lowPrice?.krw,
        )
    }
}
