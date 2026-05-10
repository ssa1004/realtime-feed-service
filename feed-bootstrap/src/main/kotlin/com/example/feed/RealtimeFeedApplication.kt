package com.example.feed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot main. 다른 4 모듈을 component scan 하기 위해 base package 를
 * `com.example.feed` 로 둔다 — 모든 adapter 가 이 prefix 안에 있다.
 */
@SpringBootApplication
class RealtimeFeedApplication

fun main(args: Array<String>) {
    runApplication<RealtimeFeedApplication>(*args)
}
