package com.seah.startupsim.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 골든 테스트 전용 이벤트 정의 고정기.
 *
 * <p>이벤트 8분기 골든은 V8 시드 콘텐츠로 만들었다. 이후 마이그레이션(V12 빈도 조정, 반응 문구 조사 문법)이
 * 정의를 바꿔도 엔진 재현성 검증이 흔들리지 않도록, 전환점·선택지·반응·순간 정의를 모두 지우고 V8 마이그레이션 파일의
 * 콘텐츠 INSERT 문만 다시 실행한다. V8 파일은 Flyway 체크섬으로 고정되어 있어 이 원본이 바뀌지 않는다.
 */
public final class GoldenEvents {
    private static final String RESOURCE = "/db/migration/V8__events.sql";
    private static final String CONTENT = "INSERT INTO (turning_points|turning_point_options|reaction_templates|moment_templates) .*";

    private GoldenEvents() {}

    /** 선택지 개수 제약 트리거가 트랜잭션 끝에 검사하므로 한 트랜잭션에서 교체한다. */
    public static void pin(JdbcTemplate jdbc) {
        List<String> inserts = read().lines().filter(line -> line.matches(CONTENT)).toList();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE turning_points, turning_point_options, reaction_templates, moment_templates CASCADE");
                for (String insert : inserts) {
                    statement.execute(insert);
                }
                connection.commit();
            } catch (RuntimeException | java.sql.SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });
    }

    private static String read() {
        try (InputStream input = GoldenEvents.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("V8 마이그레이션 파일이 없습니다: " + RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
