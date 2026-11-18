package com.seah.startupsim.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventTextTest {
    private static Shop shop(String name) {
        return new Shop("A", "망원역", "11440", "CS100010", "커피-음료", name, 127, 38, 0, 1);
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @DisplayName("한글 끝 글자 받침에 따라 조사를 고른다")
    @CsvSource({
            "망원커피하나, 이/가, 망원커피하나가",
            "망원역, 이/가, 망원역이",
            "망원커피하나, 을/를, 망원커피하나를",
            "망원역, 을/를, 망원역을",
            "망원커피하나, 은/는, 망원커피하나는",
            "망원역, 은/는, 망원역은",
            "망원커피하나, 과/와, 망원커피하나와",
            "망원역, 과/와, 망원역과",
            "망원커피하나, 이에요/예요, 망원커피하나예요",
            "망원역, 이에요/예요, 망원역이에요",
            "가상 가게, 이/가, 가상 가게가"})
    void hangul(String word, String pair, String expected) {
        assertEquals(expected, EventText.attach(word, pair));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @DisplayName("숫자는 한국어 읽기, 영문은 발음 근사로 판정하고 그 밖은 두 형태를 함께 쓴다")
    @CsvSource({
            "카페24, 이/가, 카페24가",
            "포차1, 이/가, 포차1이",
            "분식3, 을/를, 분식3을",
            "빵집0, 은/는, 빵집0은",
            "카페2, 과/와, 카페2와",
            "Cafe, 이/가, Cafe가",
            "Bar, 이/가, Bar가",
            "Kitchen, 이/가, Kitchen이",
            "Coffee Shop, 이/가, Coffee Shop이",
            "BBQ King, 을/를, BBQ King을",
            "HALL, 은/는, HALL은",
            "Mart, 이/가, Mart가",
            "가게★, 이/가, 가게★이(가)",
            "커피!, 을/를, 커피!을(를)"})
    void otherScripts(String word, String pair, String expected) {
        assertEquals(expected, EventText.attach(word, pair));
    }

    @Test
    @DisplayName("치환: 조사 쌍이 있으면 받침에 맞추고, 옛 문법은 글자 그대로 둔다")
    void render() {
        assertEquals("요즘 망원커피하나가 예전만 못한 느낌이에요.",
                EventText.render("요즘 {shopName:이/가} 예전만 못한 느낌이에요.", shop("망원커피하나")));
        assertEquals("망원역 지나갈 때마다 들르는 망원커피하나예요.",
                EventText.render("{areaName} 지나갈 때마다 들르는 {shopName:이에요/예요}.", shop("망원커피하나")));
        assertEquals("망원역에서 커피-음료는 망원본점이 최고",
                EventText.render("{areaName}에서 {serviceName:은/는} {shopName:이/가} 최고", shop("망원본점")));
        assertEquals("가상 가게이 조금 밀려요", EventText.render("{shopName}이 조금 밀려요", shop("가상 가게")));
        assertEquals("$1 \\ 가게가", EventText.render("{shopName:이/가}", shop("$1 \\ 가게")));
    }

    @Test
    @DisplayName("검증기는 허용된 변수와 조사 쌍만 받고 나머지 중괄호는 거부한다")
    void validator() {
        var validator = new EventDefinitionValidator();
        var json = new ObjectMapper();
        String template = """
                {"code":"TEST_REVIEW","kind":"REVIEW","author":"동네 주민","rating":5,"conditionExpr":{},"text":"%s"}
                """;
        assertDoesNotThrow(() -> validator.reaction(json.readTree(template.formatted(
                "{shopName:이/가} 좋아요. {areaName:을/를} 걷다 {serviceName:과/와} {shopName:은/는} {shopName:이에요/예요}"))));
        for (String bad : new String[] {"{shopName:가/이} 좋아요", "{shopName:으로/로} 가요", "{ownerName} 좋아요",
                "{shopName:이/가 좋아요"}) {
            var exception = assertThrows(GameException.class, () -> validator.reaction(json.readTree(template.formatted(bad))));
            assertEquals("text: 허용되지 않은 치환 변수입니다.", exception.getMessage());
        }
    }
}