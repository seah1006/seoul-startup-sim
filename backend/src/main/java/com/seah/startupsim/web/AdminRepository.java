package com.seah.startupsim.web;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 관리자 조회 필드만 읽고 원본은 DB에서 20KB로 제한한다. */
@Repository
public class AdminRepository {
    private final JdbcTemplate jdbc;
    public AdminRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Run(long id, String source, String period, String status, int fetched,
            int inserted, int updated, int failed, String error, Instant startedAt, Instant finishedAt) {}
    public record Raw(long id, String requestKey, long size, String checksum) {}
    public record Version(long id, String source, String period, String status, Long rowCount,
            Instant createdAt, Instant activatedAt) {}
    public record Summary(String source, Instant lastSuccess, double successRate, int lastCount, boolean running) {}
    private final org.springframework.jdbc.core.RowMapper<Run> runMapper = (rs, n) -> new Run(rs.getLong("id"),
            rs.getString("source"), rs.getString("target_period"), rs.getString("status"), rs.getInt("fetched"),
            rs.getInt("inserted"), rs.getInt("updated"), rs.getInt("failed"), rs.getString("error_message"),
            rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    public List<Run> runs(String source, String status, int page) {
        return jdbc.query("SELECT * FROM ingest_runs WHERE (? = '' OR source = ?) AND (? = '' OR status = ?) ORDER BY started_at DESC, id DESC LIMIT 21 OFFSET ?",
                runMapper, source, source, status, status, page * 20);
    }
    public Run run(long id) { return jdbc.query("SELECT * FROM ingest_runs WHERE id = ?", runMapper, id).stream().findFirst().orElse(null); }
    public Summary summary(String source) {
        return jdbc.queryForObject("""
                SELECT (SELECT max(finished_at) FROM ingest_runs WHERE source = ? AND status = 'SUCCEEDED') last_success,
                coalesce((SELECT avg(CASE WHEN status = 'SUCCEEDED' THEN 100.0 ELSE 0 END)
                    FROM (SELECT status FROM ingest_runs WHERE source = ? ORDER BY started_at DESC, id DESC LIMIT 10) recent), 0) rate,
                coalesce((SELECT fetched FROM ingest_runs WHERE source = ? ORDER BY started_at DESC, id DESC LIMIT 1), 0) last_count,
                EXISTS(SELECT 1 FROM ingest_runs WHERE source = ? AND status = 'RUNNING') running
                """, (rs, n) -> new Summary(source, rs.getTimestamp("last_success") == null ? null :
                rs.getTimestamp("last_success").toInstant(), rs.getDouble("rate"), rs.getInt("last_count"),
                rs.getBoolean("running")), source, source, source, source);
    }
    public List<Raw> raws(long id) {
        return jdbc.query("SELECT id, request_key, octet_length(payload::text) size, checksum FROM raw_payloads WHERE run_id = ? ORDER BY id",
                (rs, n) -> new Raw(rs.getLong("id"), rs.getString("request_key"), rs.getLong("size"), rs.getString("checksum")), id);
    }
    public String raw(long runId, long rawId) {
        return jdbc.query("SELECT substring(convert_to(payload::text, 'UTF8') from 1 for 20480) FROM raw_payloads WHERE run_id = ? AND id = ?",
                (rs, n) -> new String(rs.getBytes(1), java.nio.charset.StandardCharsets.UTF_8), runId, rawId).stream().findFirst().orElse(null);
    }
    public List<Version> versions() {
        return jdbc.query("SELECT * FROM data_versions ORDER BY created_at DESC, id DESC", (rs, n) ->
                new Version(rs.getLong("id"), rs.getString("source"), rs.getString("period"), rs.getString("status"),
                rs.getObject("row_count", Long.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toInstant()));
    }
}
