package com.example.feed.adapter.inbound.web

import com.example.feed.adapter.inbound.dto.FeedEventDto
import com.example.feed.adapter.inbound.dto.WindowStatsDto
import com.example.feed.application.usecase.ComputeWindowStatsUseCase
import com.example.feed.application.usecase.QueryRecentFeedUseCase
import com.example.feed.domain.SkuId
import kotlinx.coroutines.flow.map
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.function.server.json
import java.time.Duration

/**
 * Functional routing — `coRouter { }` DSL 로 모든 endpoint 가 suspend.
 *
 * `@RestController` 와 비교해:
 *   - 라우팅 테이블이 한 곳에 모여 가독성 ↑
 *   - 각 handler 가 함수로 분리돼 테스트가 단위 함수처럼 가능
 *   - WebFlux 의 `WebFilter` / `HandlerFilterFunction` 과 자연스럽게 결합
 */
@Configuration
class FeedRouter(
    private val recent: QueryRecentFeedUseCase,
    private val window: ComputeWindowStatsUseCase,
) {

    @Bean
    fun feedRoutes(): RouterFunction<ServerResponse> = coRouter {
        accept(MediaType.APPLICATION_JSON).nest {
            GET("/api/v1/feed/{skuId}/recent") { request ->
                val sku = SkuId(request.pathVariable("skuId"))
                val limit = request.queryParam("limit").orElse("50").toInt().coerceIn(1, 1000)
                val flow = recent.query(sku, limit).map(FeedEventDto::from)
                ServerResponse.ok().json().bodyAndAwait(flow)
            }

            GET("/api/v1/feed/{skuId}/window") { request ->
                val sku = SkuId(request.pathVariable("skuId"))
                val minutes = request.queryParam("minutes").orElse("5").toLong().coerceIn(1, 60)
                val w = window.computeRecent(sku, Duration.ofMinutes(minutes))
                ServerResponse.ok().json().bodyValueAndAwait(WindowStatsDto.from(w))
            }
        }
    }
}
