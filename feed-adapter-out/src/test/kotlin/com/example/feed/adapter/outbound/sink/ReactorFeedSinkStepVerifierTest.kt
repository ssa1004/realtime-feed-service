package com.example.feed.adapter.outbound.sink

import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.FeedSequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Reactor 의 [StepVerifier] 로 hot stream 의 fan-out 시퀀스를 검증.
 * Coroutines test (runTest) 와 별개로 Reactor 측 boundary 도 회귀 안전망에 포함.
 */
class ReactorFeedSinkStepVerifierTest {

    private fun trade(seq: Long) = FeedEvent.TradeMatched(
        skuId = SkuId("STREAM-001"),
        occurredAt = Instant.now(),
        sequence = FeedSequence(seq),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(100_000),
        quantity = 1,
    )

    @Test
    fun `subscribe 한 후 emit 한 이벤트들이 순서대로 도착한다`() {
        val sink = ReactorFeedSink()
        val sku = SkuId("STREAM-001")

        val flux = sink.subscribe(sku).asFlux()

        StepVerifier.create(flux)
            .then {
                runBlocking {
                    sink.emit(trade(1))
                    sink.emit(trade(2))
                    sink.emit(trade(3))
                }
            }
            .assertNext { evt -> assert((evt as FeedEvent.TradeMatched).sequence.value == 1L) }
            .assertNext { evt -> assert((evt as FeedEvent.TradeMatched).sequence.value == 2L) }
            .assertNext { evt -> assert((evt as FeedEvent.TradeMatched).sequence.value == 3L) }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    /** mono { ... } DSL 의 boundary 정상 동작 — ADR-0001. */
    @Test
    fun `mono builder 가 suspend 결과를 Mono 로 감싼다`() {
        val mono = mono {
            "boundary"
        }
        StepVerifier.create(mono)
            .expectNext("boundary")
            .verifyComplete()
    }
}
