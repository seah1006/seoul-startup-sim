package com.seah.startupsim.common;

import org.springframework.stereotype.Component;

/**
 * 화면용 금액 표기. 프론트엔드 {@code money()}(ResultCard.tsx)와 같은 규칙이다.
 *
 * <ul>
 *   <li>1만 원 미만: 원 단위 그대로 {@code 9,800원}</li>
 *   <li>1만 원 이상: 만 원 단위 반올림 {@code 3,328만 원}</li>
 *   <li>1억 원 이상: {@code 4억 4,663만 원}, 만 단위가 0이면 {@code 2억 원}</li>
 * </ul>
 *
 * <p>Thymeleaf에서는 빈 이름으로 {@code ${@money.won(value)}}처럼 쓴다.
 */
@Component("money")
public class MoneyFormat {
    private static final long MAN = 10_000;

    /** 음수는 앞에 {@code -}를 붙인다. */
    public static String won(long amount) {
        return (amount < 0 ? "-" : "") + absolute(amount);
    }

    /** 증감 표기. 양수는 {@code +}, 음수는 {@code -}, 0은 부호 없이 {@code 0원}. */
    public static String signedWon(long amount) {
        return (amount > 0 ? "+" : amount < 0 ? "-" : "") + absolute(amount);
    }

    private static String absolute(long amount) {
        long value = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (value < MAN) {
            return "%,d원".formatted(value);
        }
        // JS Math.round(abs / 10000)와 같은 0.5 올림. 덧셈 넘침을 피하려고 나머지로 계산한다.
        long man = value / MAN + (value % MAN >= MAN / 2 ? 1 : 0);
        if (man < MAN) {
            return "%,d만 원".formatted(man);
        }
        long rest = man % MAN;
        return "%,d억".formatted(man / MAN) + (rest == 0 ? "" : " %,d만".formatted(rest)) + " 원";
    }
}