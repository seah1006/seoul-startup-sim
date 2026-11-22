package com.seah.startupsim.game;

import java.util.List;

/** API 코드와 순서는 변경하지 않는다. */
public enum Mission {
    SURVIVE_3Y("3년 버티기", "12분기 동안 가게를 지키세요.", "12분기", 12, "QUARTER"),
    PROFIT_100M("누적 순이익 1억", "누적 순이익 1억 원을 달성하세요.", "1억 원", 100000000, "KRW"),
    AREA_TOP3("동네 5위 안", "상권 내 같은 업종 5위 안에 들어보세요.", "5위 이내", 5, "RANK"),
    BLACK_4("4분기 연속 흑자", "4분기 연속으로 순이익을 남기세요.", "4분기 연속", 4, "STREAK"),
    SECOND_SHOP("2호점 열기", "매장을 2곳 이상으로 늘리세요.", "2곳", 2, "SHOP");

    public record Definition(String code, String title, String description, String targetLabel) {}
    public record Progress(Long current, long target, String unit, String label) {}
    public record State(String code, String title, String description, Progress progress, boolean achieved, Integer achievedTurn) {}
    public record Ending(String code, String title, boolean achieved, Integer achievedTurn) {}
    public record Event(String code, String title, String text) {}
    public final String title;
    public final String description;
    public final String targetLabel;
    public final long target;
    public final String unit;
    Mission(String title, String description, String targetLabel, long target, String unit) {
        this.title = title; this.description = description; this.targetLabel = targetLabel; this.target = target; this.unit = unit;
    }
    public static Mission parse(String code) {
        if (code == null) return SURVIVE_3Y;
        try { return valueOf(code); } catch (IllegalArgumentException error) { throw GameException.invalid("목표 코드를 확인해 주세요."); }
    }
    public static List<Definition> definitions() {
        return java.util.Arrays.stream(values()).map(m -> new Definition(m.name(), m.title, m.description, m.targetLabel)).toList();
    }
    public boolean achieved(Long value) {
        return value != null && (this == AREA_TOP3 ? value > 0 && value <= target : value >= target);
    }
    public String label(Long value) {
        if (this == AREA_TOP3) return value == null ? "순위 없음 / 5위 이내" : value + "위 / 5위 이내";
        if (this == PROFIT_100M) return com.seah.startupsim.common.MoneyFormat.won(value == null ? 0 : value) + " / 1억 원";
        return (value == null ? 0 : value) + " / " + target + (this == SECOND_SHOP ? "곳" : "분기");
    }
}
