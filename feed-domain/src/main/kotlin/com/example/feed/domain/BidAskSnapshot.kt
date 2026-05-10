package com.example.feed.domain

/**
 * 한 SKU 의 best ASK / best BID 스냅샷. 실제 호가창 전체가 아니라 가장 좋은 양면 호가만.
 *
 * `null` 은 해당 사이드에 호가가 없음 (시장 한쪽이 비어있음) 을 의미한다 — 도메인 표현으로
 * `Money(0)` 와 다르게 두는 게 안전하다 (0원 호가는 정상 가격, 호가 부재는 비정상이 아니라 빈 시장).
 */
data class BidAskSnapshot(
    val bestAsk: Money?,
    val bestBid: Money?,
) {
    /**
     * 스프레드 (ASK - BID). 한쪽이라도 비어 있으면 정의되지 않아 `null`.
     */
    fun spread(): Money? {
        val a = bestAsk ?: return null
        val b = bestBid ?: return null
        return if (a >= b) a - b else null
    }

    /**
     * 매수자가 즉시 매수 가능한가 (ASK >= BID 라는 호가창 정합성).
     */
    fun isCrossed(): Boolean {
        val a = bestAsk ?: return false
        val b = bestBid ?: return false
        return a < b
    }
}
