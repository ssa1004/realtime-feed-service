package com.example.feed.domain

import java.util.UUID

/**
 * SKU 식별자. 도메인이 다루는 모든 곳에서 raw `String` 대신 사용한다.
 * value class 로 박스 비용 없이 타입 안전성을 얻는다.
 */
@JvmInline
value class SkuId(val value: String) {
    init {
        require(value.isNotBlank()) { "SkuId 는 공백일 수 없다" }
        require(value.length <= 64) { "SkuId 길이 제한 64" }
    }
}

/**
 * 거래 참여자 식별자. UUID 백엔드.
 */
@JvmInline
value class TraderId(val value: UUID)

/**
 * 거래 식별자. resell-orderbook 의 Trade.id 와 동일한 의미를 가진다.
 */
@JvmInline
value class TradeId(val value: UUID)

/**
 * 단조 증가 시퀀스. SharedFlow replay 시 클라이언트가 마지막 본 위치 이후만 처리하도록 돕는다.
 *
 * 이름을 `Sequence` 가 아닌 `FeedSequence` 로 둔 이유 — `kotlin.sequences.Sequence` 가
 * 모든 Kotlin 파일에 자동 import 되므로, 같은 이름의 도메인 타입을 쓰면 `.asSequence()`
 * 같은 표준 API 를 같이 쓰는 파일에서 이름 충돌이 난다. 도메인 타입은 명시적 접두사로 분리한다.
 */
@JvmInline
value class FeedSequence(val value: Long) : Comparable<FeedSequence> {
    override fun compareTo(other: FeedSequence): Int = value.compareTo(other.value)
}
