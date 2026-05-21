package com.example.feed.domain

import java.time.Instant

/**
 * Feed 가 클라이언트로 흘려보내는 이벤트.
 *
 * sealed interface 로 닫아 두어 새 종류가 추가되면 패턴 매칭 (`when`) 이 컴파일러 단계에서
 * exhaustiveness 를 강제한다. 실수로 새 케이스 처리를 누락하지 않게 한다.
 */
sealed interface FeedEvent {
    val skuId: SkuId
    val occurredAt: Instant
    val sequence: FeedSequence

    /**
     * 체결 이벤트. bid-ask-marketplace 의 `market.tradematched` 토픽이 1:1 로 매핑된다.
     *
     * @param price 체결가 (maker price 정책 — bid-ask-marketplace 의 [MatchEngine] 참고)
     * @param quantity 체결 수량 (현재 도메인은 1 SKU 1 수량 가정이지만 미래 확장 위해 표현)
     */
    data class TradeMatched(
        override val skuId: SkuId,
        override val occurredAt: Instant,
        override val sequence: FeedSequence,
        val tradeId: TradeId,
        val price: Money,
        val quantity: Int,
    ) : FeedEvent {
        init {
            require(quantity >= 1) { "체결 수량은 1 이상" }
        }
    }

    /**
     * 호가창 스냅샷 이벤트 (best ASK / best BID). bid-ask-marketplace 의 `market.bidplaced` /
     * `market.listingplaced` 등 호가 변동 이벤트의 결과로 갱신된다.
     */
    data class BidAskUpdated(
        override val skuId: SkuId,
        override val occurredAt: Instant,
        override val sequence: FeedSequence,
        val snapshot: BidAskSnapshot,
    ) : FeedEvent

    /**
     * 시스템 신호 — 클라이언트가 self-test / heartbeat 용으로 받는다.
     */
    data class Heartbeat(
        override val skuId: SkuId,
        override val occurredAt: Instant,
        override val sequence: FeedSequence,
    ) : FeedEvent
}
