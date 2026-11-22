package com.seah.startupsim.game;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이벤트 문구의 치환 변수. 가게·상권·업종 이름과 화이트리스트 DATA 변수를 허용하며,
 * 뒤에 {@code :이/가}처럼 조사 쌍을 붙이면 값의 끝 글자 받침에 맞는 조사를 고른다.
 * 예: {@code {shopName:이/가}} → "망원커피하나가", "망원역이".
 *
 * <p>조사 쌍이 없는 옛 문법({@code {shopName}이})은 글자 그대로 둔다. 이미 저장된 반응과 이벤트 골든의 출력이 바뀌지 않는다.
 */
public final class EventText {
    /** 왼쪽은 받침 있는 말 뒤, 오른쪽은 받침 없는 말 뒤. */
    public static final Map<String, String[]> PARTICLES = Map.of(
            "이/가", new String[] {"이", "가"},
            "을/를", new String[] {"을", "를"},
            "은/는", new String[] {"은", "는"},
            "과/와", new String[] {"과", "와"},
            "이에요/예요", new String[] {"이에요", "예요"});
    private static final Pattern TOKEN = Pattern.compile(
            "\\{(shopName|areaName|serviceName)(?::(이/가|을/를|은/는|과/와|이에요/예요))?}");
    private static final Pattern DATA_TOKEN = Pattern.compile(
            "\\{area\\.(quarterLabel|salesChangePct|storeChange|openings|closings|floatingPopChangePct)}");
    /** 숫자를 한국어로 읽었을 때 받침 여부: 영 일 이 삼 사 오 육 칠 팔 구. */
    private static final boolean[] DIGITS = {true, true, false, true, false, false, true, true, true, false};

    private EventText() {}

    public static String render(String text, Shop shop) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "shopName" -> shop.shopName();
                case "areaName" -> shop.areaName();
                default -> shop.serviceName();
            };
            String pair = matcher.group(2);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(pair == null ? value : attach(value, pair)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** DATA 숫자는 부호를 포함한 정수로 표시하고 당시 값을 스냅샷에 고정한다. */
    public static String render(String text, Shop shop, Map<String, Object> variables) {
        Matcher matcher = DATA_TOKEN.matcher(render(text, shop));
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = variables.get("area." + matcher.group(1));
            String replacement = value instanceof Number number
                    ? String.format(java.util.Locale.ROOT, "%+d", Math.round(number.doubleValue()))
                    : value == null ? "자료 없음" : value.toString();
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** 검증용: 허용된 치환 변수를 모두 지운 나머지. 여기에 중괄호가 남으면 허용되지 않은 변수다. */
    public static String withoutTokens(String text) {
        return DATA_TOKEN.matcher(TOKEN.matcher(text).replaceAll("")).replaceAll("");
    }

    /** 끝 글자 받침을 판정할 수 없으면 "이(가)"처럼 두 형태를 함께 쓴다. */
    public static String attach(String word, String pair) {
        String[] forms = PARTICLES.get(pair);
        if (forms == null) {
            throw new IllegalArgumentException("허용되지 않은 조사입니다: " + pair);
        }
        Boolean coda = hasFinalConsonant(word);
        return word + (coda == null ? forms[0] + "(" + forms[1] + ")" : coda ? forms[0] : forms[1]);
    }

    /**
     * 끝 글자의 받침 여부. 한글 음절은 종성으로, 숫자는 한국어 읽기로, 영문은 발음 근사
     * (l·m·n·ng로 끝나거나 모음 뒤 b·k·p·t로 끝나면 받침)로 판정한다. 그 밖의 글자는 null.
     */
    static Boolean hasFinalConsonant(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String text = word.strip();
        char last = text.charAt(text.length() - 1);
        if (last >= '가' && last <= '힣') {
            return (last - '가') % 28 != 0;
        }
        if (last >= '0' && last <= '9') {
            return DIGITS[last - '0'];
        }
        char lower = Character.toLowerCase(last);
        if (lower < 'a' || lower > 'z') {
            return null;
        }
        if ("lmn".indexOf(lower) >= 0 || text.toLowerCase().endsWith("ng")) {
            return true;
        }
        char before = text.length() > 1 ? Character.toLowerCase(text.charAt(text.length() - 2)) : ' ';
        return "bkpt".indexOf(lower) >= 0 && "aeiou".indexOf(before) >= 0;
    }
}
