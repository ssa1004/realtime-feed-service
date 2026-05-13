package com.example.feed.adapter.inbound.security

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WebSocketOriginFilter] 단위 테스트.
 *
 * 검증 포인트:
 *   1. WebSocket / SSE 경로 (`/ws/feed/...`, `/api/v1/feed/{sku}/stream`) 에 한해 Origin 검사.
 *   2. REST `/api/v1/feed/{sku}/recent` 와 같은 일반 path 는 통과.
 *   3. Origin 헤더가 없으면 통과 (server-to-server / 동일 출처 호출).
 *   4. allowlist 에 들어 있는 origin 만 통과, 그 외 403.
 */
class WebSocketOriginFilterTest {

    private val passThroughChain = WebFilterChain { Mono.empty() }

    @Test
    fun `WS path Origin 미허용이면 403`() {
        val filter = WebSocketOriginFilter(raw = "https://feed.example.com")
        val request = MockServerHttpRequest.get("/ws/feed/SKU-1")
            .header("Origin", "https://evil.example.com")
            .build()
        val exchange = MockServerWebExchange.from(request)

        filter.filter(exchange, passThroughChain).block()
        assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
    }

    @Test
    fun `WS path 허용 origin 은 통과`() {
        val filter = WebSocketOriginFilter(raw = "https://feed.example.com, https://admin.example.com")
        val request = MockServerHttpRequest.get("/ws/feed/SKU-1")
            .header("Origin", "https://feed.example.com")
            .build()
        val exchange = MockServerWebExchange.from(request)

        filter.filter(exchange, passThroughChain).block()
        assertTrue(exchange.response.statusCode == null || exchange.response.statusCode == HttpStatus.OK)
    }

    @Test
    fun `SSE stream path Origin 검사`() {
        val filter = WebSocketOriginFilter(raw = "")
        val request = MockServerHttpRequest.get("/api/v1/feed/SKU-1/stream")
            .header("Origin", "https://evil.example.com")
            .build()
        val exchange = MockServerWebExchange.from(request)

        filter.filter(exchange, passThroughChain).block()
        assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
    }

    @Test
    fun `REST recent path 는 Origin 검사 우회`() {
        val filter = WebSocketOriginFilter(raw = "")
        val request = MockServerHttpRequest.get("/api/v1/feed/SKU-1/recent")
            .header("Origin", "https://evil.example.com")
            .build()
        val exchange = MockServerWebExchange.from(request)

        filter.filter(exchange, passThroughChain).block()
        assertTrue(exchange.response.statusCode == null || exchange.response.statusCode == HttpStatus.OK)
    }

    @Test
    fun `Origin 헤더 없으면 통과 (server-to-server)`() {
        val filter = WebSocketOriginFilter(raw = "https://feed.example.com")
        val request = MockServerHttpRequest.get("/ws/feed/SKU-1").build()
        val exchange = MockServerWebExchange.from(request)

        filter.filter(exchange, passThroughChain).block()
        assertTrue(exchange.response.statusCode == null || exchange.response.statusCode == HttpStatus.OK)
    }
}
