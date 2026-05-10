package com.example.feed.adapter.inbound.ws

import com.example.feed.application.port.FeedCache
import com.example.feed.application.port.FeedSink
import com.example.feed.application.usecase.StreamFeedUseCase
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.Sequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.URI
import java.security.Principal
import java.time.Instant
import java.util.UUID

/**
 * WebSocket handler 단위 테스트 — 실제 HTTP server 를 띄우지 않고 [WebSocketSession] 을
 * fake 로 만들어 handler 의 send 출력만 검증한다.
 *
 * sampleWindowMs 가 1ms 인 use case 를 직접 만들어 sample 의 윈도우 폐쇄 전에 cancellation
 * 이 발생하지 않도록 한다 (default 100ms 는 단일 이벤트 테스트에서 race 가능).
 */
class FeedWebSocketHandlerTest {

    private val mapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    private val sku = SkuId("WS-NIKE-001")

    private val sampleEvent = FeedEvent.TradeMatched(
        skuId = sku,
        occurredAt = Instant.parse("2026-05-09T00:00:00Z"),
        sequence = Sequence(1),
        tradeId = TradeId(UUID.randomUUID()),
        price = Money(150_000),
        quantity = 1,
    )

    private val sink = object : FeedSink {
        override suspend fun emit(event: FeedEvent) {}
        override fun subscribe(skuId: SkuId): Flow<FeedEvent> = flowOf(sampleEvent)
    }
    private val cache = object : FeedCache {
        override suspend fun cache(event: FeedEvent) {}
        override suspend fun recent(skuId: SkuId, limit: Int): List<FeedEvent> = listOf(sampleEvent)
    }

    @Test
    fun `WS handler 가 catch-up 이벤트를 JSON text 메시지로 보낸다`() {
        val session = FakeSession(uri = URI.create("ws://localhost/ws/feed/WS-NIKE-001"))
        val handler = FeedWebSocketHandler(StreamFeedUseCase(sink, cache), mapper)

        StepVerifier.create(handler.handle(session))
            .verifyComplete()

        val sent = session.sentPayloads
        // catch-up 1건은 sample 우회 — recent() 의 결과가 즉시 emit 된다.
        assert(sent.isNotEmpty()) { "최소 1 메시지가 전송되어야 한다 actual=${sent.size}" }
        val json = sent[0]
        assert(json.contains("TRADE_MATCHED"))
        assert(json.contains("WS-NIKE-001"))
        assert(json.contains("150000"))
    }

    @Test
    fun `잘못된 path 는 즉시 close`() {
        val session = FakeSession(uri = URI.create("ws://localhost/ws/feed/"))
        val handler = FeedWebSocketHandler(StreamFeedUseCase(sink, cache), mapper)

        StepVerifier.create(handler.handle(session))
            .verifyComplete()
        assert(session.closeCalled) { "skuId 없으면 close 되어야 한다" }
    }

    /** 테스트용 fake [WebSocketSession] — 보낸 메시지를 buffer 에 쌓는다. */
    private class FakeSession(private val uri: URI) : WebSocketSession {
        val sentPayloads = mutableListOf<String>()
        var closeCalled = false

        override fun getId(): String = "fake-session"
        override fun getHandshakeInfo(): HandshakeInfo {
            return HandshakeInfo(uri, HttpHeaders(), Mono.empty<Principal>(), null)
        }
        override fun bufferFactory() = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance
        override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()
        override fun receive(): Flux<WebSocketMessage> = Flux.empty()
        override fun send(messages: Publisher<WebSocketMessage>): Mono<Void> {
            return Flux.from(messages)
                .doOnNext { msg ->
                    sentPayloads.add(msg.payloadAsText)
                }
                .then()
        }
        override fun isOpen(): Boolean = !closeCalled
        override fun close(): Mono<Void> {
            closeCalled = true
            return Mono.empty()
        }
        override fun close(status: org.springframework.web.reactive.socket.CloseStatus): Mono<Void> = close()
        override fun closeStatus(): Mono<org.springframework.web.reactive.socket.CloseStatus> = Mono.empty()
        override fun textMessage(payload: String): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.TEXT, bufferFactory().wrap(payload.toByteArray()))
        override fun binaryMessage(payloadFactory: java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory, org.springframework.core.io.buffer.DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory()))
        override fun pingMessage(payloadFactory: java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory, org.springframework.core.io.buffer.DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory()))
        override fun pongMessage(payloadFactory: java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory, org.springframework.core.io.buffer.DataBuffer>): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory()))
    }
}
