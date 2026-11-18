package com.seah.startupsim.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 골든 테스트 전용 게임 설정 고정기.
 *
 * <p>엔진 재현성 검증은 밸런스 조정과 독립이어야 한다. 골든 JSON은 V5~V9 설정(V10 밸런스 조정 전)으로
 * 만들었으므로, 게임 생성 전에 {@code game_settings} 전체를 그 당시 값으로 교체한다. 이후 마이그레이션이
 * 임대료·원가율 등을 바꾸거나 새 키(예: 업종별 원가율)를 추가해도 골든 결과는 바뀌지 않는다.
 */
public final class GoldenSettings {
    private static final String RESOURCE = "/game/golden-settings.json";

    private GoldenSettings() {}

    /** 기존 설정을 모두 지우고 골든 생성 당시 값만 넣는다. 새 키가 섞이지 않도록 덮어쓰기가 아닌 교체다. */
    public static void pin(JdbcTemplate jdbc, ObjectMapper json) {
        JsonNode settings = read(json);
        jdbc.update("DELETE FROM game_settings");
        settings.properties().forEach(entry -> jdbc.update("""
                INSERT INTO game_settings (key, value) VALUES (?, ?::jsonb)
                """, entry.getKey(), entry.getValue().toString()));
    }

    private static JsonNode read(ObjectMapper json) {
        try (InputStream input = GoldenSettings.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("골든 설정 파일이 없습니다: " + RESOURCE);
            }
            return json.readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
