package com.example.feed.adapter.inbound.ws

import com.example.feed.adapter.inbound.dto.FeedEventDto
import com.example.feed.application.usecase.StreamFeedUseCase
import com.example.feed.domain.SkuId
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

/**
 * WebSocket handler — `/ws/feed/{skuId}` path 로 들어온 클라이언트에게 SKU feed 를 fan-out.
 *
 * URI path variable 추출 — Spring 의 WebFlux WebSocket 은 URI matcher 를 직접 제공하지 않아
 * `session.handshakeInfo.uri` 에서 path segment 를 직접 파싱한다.
 *
 * 캔슬 전파 (ADR-0006):
 *   1) 클라이언트가 close → session.send 가 에러로 종료 → 반환된 Mono 가 종료
 *   2) Reactor 가 upstream Flux (= sink subscription) 를 cancel
 *   3) cancel 신호가 [com.example.feed.adapter.outbound.sink.ReactorFeedSink.subscribe] 로 전파
 *
 * 한 SKU 에 N 개의 클라이언트가 붙으면 sink 의 multicast 가 N 개 모두에게 broadcast 한다.
 * slow consumer 는 `Sinks.many().multicast().onBackpressureBuffer` 의 버퍼 한도를 넘기면
 * `EmitResult.FAIL_OVERFLOW` 로 떨어진다 (ADR-0007).
 */
@Configuration
class FeedWebSocketConfig(
    private val handler: FeedWebSocketHandler,
) {
    @Bean
    fun feedHandlerMapping(): HandlerMapping {
        val mapping = mapOf("/ws/feed/**" to handler)
        return SimpleUrlHandlerMapping(mapping, /* order = */ -1)
    }
}

@org.springframework.stereotype.Component
class FeedWebSocketHandler(
    private val streamUseCase: StreamFeedUseCase,
    private val mapper: ObjectMapper,
) : WebSocketHandler {

    private val log = LoggerFactory.getLogger(FeedWebSocketHandler::class.java)

    override fun handle(session: WebSocketSession): Mono<Void> {
        val sku = extractSkuId(session) ?: return session.close()

        log.info("ws subscribe sku={} sessionId={}", sku.value, session.id)

        val outbound = streamUseCase.stream(sku)
            .map { event ->
                val dto = FeedEventDto.from(event)
                session.textMessage(mapper.writeValueAsString(dto))
            }
            .asFlux()

        return session.send(outbound)
            .doFinally { signal ->
                log.info("ws close sku={} sessionId={} signal={}", sku.value, session.id, signal)
            }
    }

    private fun extractSkuId(session: WebSocketSession): SkuId? {
        val path = session.handshakeInfo.uri.path  // 예: /ws/feed/NIKE-001
        val skuRaw = path.substringAfterLast("/ws/feed/", missingDelimiterValue = "")
            .trim('/').takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { SkuId(skuRaw) }.getOrNull()
    }
}
