package com.example.feed.adapter.inbound.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * WebSocket / SSE handshake 의 `Origin` 검증.
 *
 * Spring WebFlux 의 WebSocket 핸들러는 기본적으로 `Origin` 헤더를 강제하지 않는다
 * (MVC 의 `AbstractHandshakeHandler` 와 달리 reactive 쪽은 별도 hook 이 없음).
 * 그대로 두면 임의 origin 의 페이지가 사용자 브라우저를 통해 본 서비스로 WebSocket / SSE
 * 연결을 만들 수 있어 cross-site WebSocket hijacking 의 표면이 된다 (OWASP API8).
 *
 * 본 필터는 `feed.security.allowed-origins` 의 화이트리스트에 들어 있는 Origin 만 통과시키고,
 * 그 외에는 403 으로 거절한다. 값이 비어 있으면 (기본) `Origin` 헤더가 있는 요청은 모두
 * 거절 — 브라우저가 아닌 동일 출처(server-to-server) 호출은 `Origin` 헤더가 없으므로 통과.
 *
 * WebSocket path 와 SSE path (`/ws/feed/...` 와 `/api/v1/feed/.../stream`) 에만 적용.
 * REST `recent` / `window` 는 동일 출처 + JWT 만으로 충분.
 *
 * dev 프로필에서는 비활성 — [SecurityConfig.devSecurityFilterChain] 가 권한 자체를 우회한다.
 */
@Component
@Profile("!dev")
class WebSocketOriginFilter(
    @Value("\${feed.security.allowed-origins:}") private val raw: String,
) : WebFilter {

    private val allowed: Set<String> = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        if (!isProtectedPath(path)) {
            return chain.filter(exchange)
        }
        val origin = exchange.request.headers.getFirst(HttpHeaders.ORIGIN)
            ?: return chain.filter(exchange)  // 브라우저 아닌 호출은 Origin 헤더 없음 — JWT 로만 검증

        if (origin in allowed) {
            return chain.filter(exchange)
        }
        exchange.response.statusCode = HttpStatus.FORBIDDEN
        return exchange.response.setComplete()
    }

    private fun isProtectedPath(path: String): Boolean =
        path.startsWith("/ws/feed/") || (path.startsWith("/api/v1/feed/") && path.endsWith("/stream"))
}
