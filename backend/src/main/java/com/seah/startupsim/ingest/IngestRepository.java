package com.seah.startupsim.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/** 수집 이력과 원본을 저장한다. REQUIRES_NEW로 호출자 롤백에도 장애 분석 기록을 보존한다. */
@Repository
public class IngestRepository {
    private final JdbcTemplate jdbc;

    public IngestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(String source, String period) {
        return jdbc.queryForObject(
                """
                INSERT INTO ingest_runs(source, target_period, status)
                VALUES (?, ?, 'RUNNING')
                RETURNING id
                """,
                Long.class,
                source,
                period);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(long id, CollectResult result) {
        jdbc.update(
                """
                UPDATE ingest_runs
                SET status = 'SUCCEEDED',
                    fetched = ?,
                    inserted = ?,
                    updated = ?,
                    failed = ?,
                    finished_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """,
                result.fetched(),
                result.inserted(),
                result.updated(),
                result.failed(),
                id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long id, String message) {
        jdbc.update(
                """
                UPDATE ingest_runs
                SET status = 'FAILED', error_message = ?, finished_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """,
                message,
                id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRaw(long id, String source, String key, String payload) {
        if (key == null || key.isBlank() || key.length() > 500) {
            throw new IllegalArgumentException("requestKey는 1~500자여야 합니다.");
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest);
            jdbc.update(
                    """
                    INSERT INTO raw_payloads(run_id, source, request_key, payload, checksum)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                    """,
                    id,
                    source,
                    key,
                    payload,
                    hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 사용 불가", e);
        }
    }

    /** 정리 작업도 독립 커밋하여 이후 초기화 실패와 무관하게 수집 잠금을 해제한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failStaleRuns(Instant cutoff) {
        return jdbc.update(
                """
                UPDATE ingest_runs
                SET status = 'FAILED',
                    error_message = '중단됨: 앱 재시작 시 정리',
                    finished_at = now()
                WHERE status = 'RUNNING' AND started_at < ?
                """,
                Timestamp.from(cutoff));
    }
}
