package com.example.feed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import reactor.core.publisher.Hooks

/**
 * Spring Boot main. 다른 4 모듈을 component scan 하기 위해 base package 를
 * `com.example.feed` 로 둔다 — 모든 adapter 가 이 prefix 안에 있다.
 */
@SpringBootApplication
class RealtimeFeedApplication

fun main(args: Array<String>) {
    // ADR-0005 — Reactor Context ↔ CoroutineContext ↔ MDC 자동 전파.
    // coroutine ↔ Reactor boundary 가 많은 구조라 traceId / MDC 가 끊기지 않게
    // SpringApplication 기동 전에 hook 을 켠다.
    Hooks.enableAutomaticContextPropagation()
    runApplication<RealtimeFeedApplication>(*args)
}
