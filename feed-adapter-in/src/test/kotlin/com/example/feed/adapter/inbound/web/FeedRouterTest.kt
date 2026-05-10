package com.example.feed.adapter.inbound.web

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedEventStore
import com.example.feed.application.usecase.ComputeWindowStatsUseCase
import com.example.feed.application.usecase.QueryRecentFeedUseCase
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.Sequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FeedRouterTest {

    private val sku = SkuId("NIKE-DUNK-LOW-001")
    private val now: Instant = Instant.parse("2026-05-09T00:00:00Z")

    private fun trade(seq: Long, price: Long) = FeedEvent.TradeMatched(
        skuId = sku,
        occurredAt = now.minusSeconds(60 - seq),
        sequence = Sequence(seq),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(price),
        quantity = 1,
    )

    private val store = object : FeedEventStore {
        override suspend fun append(event: FeedEvent) {}
        override fun recent(skuId: SkuId, limit: Int): Flow<FeedEvent> =
            listOf(trade(3, 130), trade(2, 120), trade(1, 110)).asFlow()
        override fun between(skuId: SkuId, from: Instant, to: Instant): Flow<FeedEvent> =
            listOf(trade(1, 100), trade(2, 200)).asFlow()
    }

    private val cache = object : FeedCache {
        override suspend fun cache(event: FeedEvent) {}
        override suspend fun recent(skuId: SkuId, limit: Int): List<FeedEvent> =
            emptyList() // 캐시 미스 → store 로 fallback
    }

    private val router = FeedRouter(
        recent = QueryRecentFeedUseCase(cache, store),
        window = ComputeWindowStatsUseCase(store, Clock.fixed(now, ZoneOffset.UTC)),
    ).feedRoutes()

    private val client = WebTestClient.bindToRouterFunction(router).build()

    @Test
    fun `recent endpoint 가 cache 미스 시 store fallback 으로 응답한다`() {
        client.get().uri("/api/v1/feed/${sku.value}/recent?limit=10")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[0].type").isEqualTo("TRADE_MATCHED")
            .jsonPath("$[0].skuId").isEqualTo(sku.value)
    }

    @Test
    fun `window endpoint 가 VWAP 와 함께 응답한다`() {
        client.get().uri("/api/v1/feed/${sku.value}/window?minutes=5")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.skuId").isEqualTo(sku.value)
            .jsonPath("$.tradeCount").isEqualTo(2)
            .jsonPath("$.totalVolume").isEqualTo(2)
            .jsonPath("$.vwapKrw").isEqualTo(150) // (100 + 200) / 2
            .jsonPath("$.highPriceKrw").isEqualTo(200)
            .jsonPath("$.lowPriceKrw").isEqualTo(100)
    }

    /** RouterFunctions 가 unused 로 잡히지 않게 한 줄. */
    @Suppress("unused")
    private val _routerHelper = RouterFunctions::class
}
