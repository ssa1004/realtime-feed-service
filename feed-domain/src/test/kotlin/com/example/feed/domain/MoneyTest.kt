package com.example.feed.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MoneyTest {
    @Test
    fun `음수 금액은 거부한다`() {
        assertThatThrownBy { Money(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `덧셈과 뺄셈이 비음수 불변식을 지킨다`() {
        val a = Money(10_000)
        val b = Money(3_000)
        assertThat(a + b).isEqualTo(Money(13_000))
        assertThat(a - b).isEqualTo(Money(7_000))

        assertThatThrownBy { b - a }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `formatKrw 는 천 단위 구분과 원 단위로 표시한다`() {
        assertThat(Money(1_234_500).formatKrw()).isEqualTo("1,234,500원")
        assertThat(Money.ZERO.formatKrw()).isEqualTo("0원")
    }
}
