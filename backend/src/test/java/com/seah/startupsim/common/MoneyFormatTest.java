package com.seah.startupsim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyFormatTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("프론트 money()와 같은 규칙: 1만 미만 원, 1만 이상 만 원 반올림, 1억 이상 억·만")
    @CsvSource(delimiter = '|', value = {
            "0|0원",
            "9800|9,800원",
            "9999|9,999원",
            "10000|1만 원",
            "14999|1만 원",
            "15000|2만 원",
            "33280000|3,328만 원",
            "9605189|961만 원",
            "99994999|9,999만 원",
            "99995000|1억 원",
            "100000000|1억 원",
            "446630000|4억 4,663만 원",
            "200004999|2억 원",
            "1234567890123|12,345억 6,789만 원",
            "-3000000|-300만 원",
            "-9800|-9,800원",
            "-446630000|-4억 4,663만 원"})
    void won(long amount, String expected) {
        assertEquals(expected, MoneyFormat.won(amount));
    }

    @Test
    @DisplayName("증감 표기는 양수에 +를 붙이고 0은 부호가 없다")
    void signedWon() {
        assertEquals("+150만 원", MoneyFormat.signedWon(1500000));
        assertEquals("-300만 원", MoneyFormat.signedWon(-3000000));
        assertEquals("+2,500원", MoneyFormat.signedWon(2500));
        assertEquals("0원", MoneyFormat.signedWon(0));
        assertEquals("+1억 2,000만 원", MoneyFormat.signedWon(120000000));
    }

    @Test
    @DisplayName("극단값에서도 넘침 없이 형식을 지킨다")
    void extremes() {
        assertEquals("-92,233,720,368억 5,478만 원", MoneyFormat.won(Long.MIN_VALUE));
        assertEquals("92,233,720,368억 5,478만 원", MoneyFormat.won(Long.MAX_VALUE));
    }
}