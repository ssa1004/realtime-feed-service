package com.example.feed.adapter.outbound.sink

import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.Sequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ReactorFeedSinkTest {

    private fun trade(sku: String, seq: Long) = FeedEvent.TradeMatched(
        skuId = SkuId(sku),
        occurredAt = Instant.now(),
        sequence = Sequence(seq),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(150_000),
        quantity = 1,
    )

    @Test
    fun `같은 SKU 구독자는 emit 한 이벤트를 받는다`() = runTest {
        val sink = ReactorFeedSink()
        val sku = SkuId("NIKE-001")

        val received = async {
            sink.subscribe(sku).take(2).toList()
        }

        // 구독이 자리잡을 시간 — runTest 의 가상 시간이 아닌 실제 multicast 등록.
        kotlinx.coroutines.delay(50)

        sink.emit(trade("NIKE-001", 1))
        sink.emit(trade("NIKE-001", 2))

        val collected = received.await()
        assertThat(collected.map { it.sequence.value }).containsExactly(1L, 2L)
    }

    @Test
    fun `다른 SKU 의 이벤트는 받지 않는다`() = runTest {
        val sink = ReactorFeedSink()
        val target = SkuId("NIKE-001")

        val received = async {
            sink.subscribe(target).take(1).toList()
        }
        kotlinx.coroutines.delay(50)

        sink.emit(trade("NIKE-002", 1))   // 다른 SKU — 받지 말아야
        sink.emit(trade("NIKE-001", 2))   // 본 SKU — 받아야

        val collected = received.await()
        assertThat(collected).hasSize(1)
        assertThat(collected[0].sequence.value).isEqualTo(2L)
    }
}
