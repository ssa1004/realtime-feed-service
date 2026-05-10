package com.example.feed.adapter.inbound.sse

import com.example.feed.adapter.inbound.dto.FeedEventDto
import com.example.feed.application.usecase.StreamFeedUseCase
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/**
 * SSE endpoint — `text/event-stream`. 브라우저의 `EventSource` 가 그대로 받는다.
 *
 * SSE 는 server → client 단방향 + HTTP 위에서 동작 — proxy / firewall 친화적.
 * WebSocket 만큼 양방향이 필요 없는 단순 클라이언트라면 SSE 가 더 단순한 선택이다.
 *
 * Flow → Flux 변환 — Spring 의 SSE writer 가 Publisher 를 기대하므로 [asFlux] 로 변환.
 * coroutine 캔슬은 Flux subscription 캔슬로 자연스럽게 전파된다 (ADR-0006).
 */
@Configuration
class SseRouter(
    private val streamUseCase: StreamFeedUseCase,
) {

    @Bean
    fun sseRoutes(): RouterFunction<ServerResponse> = coRouter {
        GET("/api/v1/feed/{skuId}/stream") { request ->
            val sku = SkuId(request.pathVariable("skuId"))
            val flux = streamUseCase.stream(sku)
                .map { event ->
                    ServerSentEvent.builder<FeedEventDto>()
                        .id(event.sequence.value.toString())
                        .event(event.javaClass.simpleName)
                        .data(FeedEventDto.from(event))
                        .build()
                }
                .asFlux()

            ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(BodyInserters.fromServerSentEvents(flux))
                .awaitSingle()
        }
    }
}
