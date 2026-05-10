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
 */
@JvmInline
value class Sequence(val value: Long) : Comparable<Sequence> {
    override fun compareTo(other: Sequence): Int = value.compareTo(other.value)
}
