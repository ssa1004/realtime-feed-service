package com.example.feed.adapter.inbound.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

// Spring Security Reactive 설정 — auth-service 의 JWK Set 으로 JWT 검증.
//
// 보호 정책:
//   - /actuator/health/** 는 모두 허용 (k8s liveness / readiness probes).
//   - /actuator/info, /actuator/metrics, /actuator/prometheus 는 인증 필요
//     (운영 metric / 빌드 정보 노출 차단 — OWASP API5 / API8).
//   - /api/v1/feed/** 와 /ws/feed/** 는 인증 필요 (auth-service JWT).
//
// dev 프로필에서는 인증을 우회 — 통합 demo 용.
// 운영 (prod 프로필) 에서는 강제 인증.
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    // prod / 그 외 — auth-service JWK Set 으로 JWT 검증.
    // issuerUri 는 application.yml 의 jwk-set-uri 키로 주입한다.
    @Bean
    @Profile("!dev")
    fun jwtSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeExchange {
                // k8s probes 만 공개 — prometheus / metrics / info 는 인증 뒤로.
                it.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                it.pathMatchers("/actuator/**").authenticated()
                // CORS preflight(OPTIONS)는 Authorization 헤더를 싣지 않으므로 인증 전에 통과시켜야
                // 브라우저가 본 요청을 보낼 수 있다 — 아래 denyAll 에 막히지 않게 두는 의도적 예외.
                it.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.pathMatchers("/api/v1/feed/**").authenticated()
                it.pathMatchers("/ws/feed/**").authenticated()
                it.anyExchange().denyAll()
            }
            // default JwtDecoder — application.yml 의 jwk-set-uri 사용
            .oauth2ResourceServer { rs -> rs.jwt { } }
            .build()
    }

    // dev — 통합 demo / 로컬 실행 시 인증 우회.
    // AGENTS.md 의 보안 위생 원칙: dev 라도 endpoint 자체는 정의해 두어 prod 와 차이가 분명.
    @Bean
    @Profile("dev")
    fun devSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeExchange { it.anyExchange().permitAll() }
            .build()
    }
}
