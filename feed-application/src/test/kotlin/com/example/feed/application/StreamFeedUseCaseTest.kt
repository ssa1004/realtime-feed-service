package com.example.feed.application

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedSink
import com.example.feed.application.usecase.StreamFeedUseCase
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.FeedSequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StreamFeedUseCaseTest {

    private val sku = SkuId("NIKE-DUNK-LOW-001")

    private fun trade(seq: Long, price: Long) = FeedEvent.TradeMatched(
        skuId = sku,
        occurredAt = Instant.now(),
        sequence = FeedSequence(seq),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(price),
        quantity = 1,
    )

    @Test
    fun `catch-up 이 먼저, 뒤이어 realtime 이 흐른다`() = runTest {
        val cached = listOf(trade(1, 100), trade(2, 110), trade(3, 120))
        val realtime = listOf(trade(4, 130), trade(5, 140))

        val cache = object : FeedCache {
            override suspend fun cache(event: FeedEvent) {}
            override suspend fun recent(skuId: SkuId, limit: Int): List<FeedEvent> = cached
        }
        val sink = object : FeedSink {
            override suspend fun emit(event: FeedEvent) {}
            override fun subscribe(skuId: SkuId): Flow<FeedEvent> = flowOf(*realtime.toTypedArray())
        }

        val sut = StreamFeedUseCase(sink, cache)
        // sample(0) 은 안되므로 큰 윈도우, 그리고 take 로 종료. 작은 케이스에서는 sample 이
        // 결정성 깨질 수 있으니 충분히 큰 sampleWindowMs.
        val collected = sut.stream(sku, catchUpLimit = 3, sampleWindowMs = 1)
            .take(cached.size)
            .toList()

        // catch-up 이 먼저 들어와야 한다 — merge 의 순서는 "먼저 emit 한 쪽" 으로 결정되는데
        // realtime 이 sample 로 첫 emit 이 늦으므로 자연스럽게 catch-up 이 선행된다.
        assertThat(collected.map { it.sequence.value }).containsExactly(1L, 2L, 3L)
    }
}
