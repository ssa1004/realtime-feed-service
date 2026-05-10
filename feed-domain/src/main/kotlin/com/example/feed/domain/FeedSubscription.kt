package com.example.feed.domain

import java.time.Instant
import java.util.UUID

/**
 * 한 클라이언트의 구독 세션. WebSocket / SSE 가 동일하게 이 타입을 사용한다.
 *
 * 상태 전이가 명확하므로 sealed class 로 표현 — 각 상태가 가진 정보가 다르다.
 */
data class FeedSubscription(
    val id: UUID,
    val skuId: SkuId,
    val traderId: TraderId,
    val state: State,
    val openedAt: Instant,
) {
    sealed class State {
        /** 구독 시작. backpressure 윈도우가 열려 있고 클라이언트가 따라잡을 수 있다. */
        data object Active : State()

        /**
         * 클라이언트가 느려져서 backpressure buffer 가 한도를 넘긴 상태.
         * sample 정책 (ADR-0003) 으로 일부 이벤트가 drop 되고 있음.
         */
        data class Lagging(val droppedSinceLastUpdate: Long) : State()

        /** 구독이 종료됨 (정상 close / cancel / 서버 shutdown). */
        data class Closed(val reason: CloseReason) : State()
    }

    enum class CloseReason {
        CLIENT_CLOSE,        // 클라이언트가 명시적으로 종료
        SERVER_SHUTDOWN,     // pod 종료 (graceful)
        AUTHENTICATION_LOST, // JWT 만료 또는 무효화
        UPSTREAM_ERROR,      // Kafka consumer 등 upstream 장애
        TIMEOUT,             // idle timeout
    }

    fun lag(dropped: Long): FeedSubscription =
        copy(state = State.Lagging(dropped))

    fun close(reason: CloseReason): FeedSubscription =
        copy(state = State.Closed(reason))
}
