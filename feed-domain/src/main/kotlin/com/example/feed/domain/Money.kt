package com.example.feed.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * 금액 도메인 타입. 통화는 KRW 가정 (bid-ask-marketplace 와 동일).
 *
 * 정수 단위 (원) 까지만 다루므로 내부 표현은 `Long`. 외부 노출용 포맷은 [formatKrw].
 */
@JvmInline
value class Money(val krw: Long) : Comparable<Money> {
    init {
        require(krw >= 0) { "Money 는 음수일 수 없다" }
    }

    operator fun plus(other: Money): Money = Money(krw + other.krw)
    operator fun minus(other: Money): Money {
        val diff = krw - other.krw
        require(diff >= 0) { "차감 결과가 음수가 될 수 없다" }
        return Money(diff)
    }

    /** VWAP 등 평균/가중 계산을 위해 잠시 BigDecimal 로 빠진다. */
    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(krw)

    override fun compareTo(other: Money): Int = krw.compareTo(other.krw)

    companion object {
        val ZERO = Money(0)

        fun of(krw: Long): Money = Money(krw)

        /** VWAP 계산 결과를 다시 Money 로 — 반올림 정책은 HALF_UP. */
        fun fromBigDecimal(bd: BigDecimal): Money = Money(bd.setScale(0, RoundingMode.HALF_UP).toLong())
    }
}

/** 도메인 어휘 확장 — extension function 으로 도메인 의도를 드러낸다. */
fun Money.formatKrw(): String =
    NumberFormat.getNumberInstance(Locale.KOREA).format(krw) + "원"
