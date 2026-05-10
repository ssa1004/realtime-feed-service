package com.example.feed.application

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedEventStore
import com.example.feed.application.port.FeedSink
import com.example.feed.application.usecase.IngestTradeMatchedUseCase
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.Sequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class IngestTradeMatchedUseCaseTest {

    /** 테스트 더블 — Mockito 의존 없이 fake 로 가시성 높이기. */
    private class FakeSink : FeedSink {
        val emitted = ConcurrentLinkedQueue<FeedEvent>()
        override suspend fun emit(event: FeedEvent) { emitted.add(event) }
        override fun subscribe(skuId: SkuId): Flow<FeedEvent> = emptyFlow()
    }

    private class FakeStore : FeedEventStore {
        val saved = ConcurrentLinkedQueue<FeedEvent>()
        override suspend fun append(event: FeedEvent) { saved.add(event) }
        override fun recent(skuId: SkuId, limit: Int): Flow<FeedEvent> = emptyFlow()
        override fun between(skuId: SkuId, from: Instant, to: Instant): Flow<FeedEvent> = emptyFlow()
    }

    private class FakeCache : FeedCache {
        val cached = ConcurrentLinkedQueue<FeedEvent>()
        override suspend fun cache(event: FeedEvent) { cached.add(event) }
        override suspend fun recent(skuId: SkuId, limit: Int): List<FeedEvent> = emptyList()
    }

    @Test
    fun `이벤트 1건이 sink와 store, cache 모두에 도달한다`() = runTest {
        val sink = FakeSink()
        val store = FakeStore()
        val cache = FakeCache()
        val sut = IngestTradeMatchedUseCase(sink, store, cache)

        val event = FeedEvent.TradeMatched(
            skuId = SkuId("NIKE-001"),
            occurredAt = Instant.parse("2026-05-09T00:00:00Z"),
            sequence = Sequence(1),
            tradeId = TradeId(UUID.randomUUID()),
            price = Money(150_000),
            quantity = 1,
        )

        sut.handle(event)

        assertThat(sink.emitted).containsExactly(event)
        assertThat(cache.cached).containsExactly(event)
        assertThat(store.saved).containsExactly(event)
    }

    @Test
    fun `store 실패가 sink fan-out 을 막지 않는다`() = runTest {
        val sink = FakeSink()
        val store = object : FeedEventStore {
            override suspend fun append(event: FeedEvent) {
                throw RuntimeException("DB down")
            }
            override fun recent(skuId: SkuId, limit: Int): Flow<FeedEvent> = emptyFlow()
            override fun between(skuId: SkuId, from: Instant, to: Instant): Flow<FeedEvent> = emptyFlow()
        }
        val cache = FakeCache()
        val sut = IngestTradeMatchedUseCase(sink, store, cache)

        val event = FeedEvent.TradeMatched(
            skuId = SkuId("NIKE-002"),
            occurredAt = Instant.now(),
            sequence = Sequence(2),
            tradeId = TradeId(UUID.randomUUID()),
            price = Money(200_000),
            quantity = 1,
        )

        sut.handle(event)

        assertThat(sink.emitted).containsExactly(event)
        assertThat(cache.cached).containsExactly(event)
    }
}
