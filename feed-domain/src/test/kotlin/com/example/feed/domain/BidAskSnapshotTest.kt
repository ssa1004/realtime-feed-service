package com.example.feed.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BidAskSnapshotTest {

    @Test
    fun `한쪽이 비면 spread 는 정의되지 않는다`() {
        val s = BidAskSnapshot(bestAsk = Money(150_000), bestBid = null)
        assertThat(s.spread()).isNull()
        assertThat(s.isCrossed()).isFalse()
    }

    @Test
    fun `정상 호가창은 spread 가 ASK - BID`() {
        val s = BidAskSnapshot(bestAsk = Money(150_000), bestBid = Money(140_000))
        assertThat(s.spread()).isEqualTo(Money(10_000))
        assertThat(s.isCrossed()).isFalse()
    }

    @Test
    fun `BID 가 ASK 보다 높으면 crossed 상태`() {
        val s = BidAskSnapshot(bestAsk = Money(140_000), bestBid = Money(150_000))
        assertThat(s.spread()).isNull()
        assertThat(s.isCrossed()).isTrue()
    }
}
