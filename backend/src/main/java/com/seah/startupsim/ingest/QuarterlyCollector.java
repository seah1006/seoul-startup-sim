package com.seah.startupsim.ingest;

import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 한 분기 전체를 원자적으로 적재하되 원본은 독립 트랜잭션으로 보존한다. */
public final class QuarterlyCollector implements Collector {
    public record Field(String column, String external) {}
    private final String source;
    private final String service;
    private final String table;
    private final boolean industry;
    private final List<Field> fields;
    private final SeoulOpenApiClient client;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public QuarterlyCollector(String source, String service, String table, boolean industry,
            List<Field> fields, SeoulOpenApiClient client, JdbcTemplate jdbc, PlatformTransactionManager tx) {
        this.source = source;
        this.service = service;
        this.table = table;
        this.industry = industry;
        this.fields = fields;
        this.client = client;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(tx);
    }

    /** 소수 절삭과 double 문자열 변환 없이 정수와 정수 백분율을 검증한다. */
    static Long exactLong(JsonNode value, String field) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            BigDecimal number;
            if (value.isNumber()) {
                number = value.decimalValue();
            } else if (value.isString()) {
                number = new BigDecimal(value.asText());
            } else {
                throw new IllegalArgumentException("숫자 타입이 아닙니다: " + field);
            }
            return number.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("정수 범위 또는 소수부 오류: " + field, e);
        }
    }

    @Override
    public String source() { return source; }

    @Override
    public CollectResult collect(IngestContext context) {
        QuarterBackfillService.validate(context.targetPeriod());
        return transaction.execute(status -> {
            String quarter = context.targetPeriod();
            Long version = jdbc.queryForObject("INSERT INTO data_versions(source,period,status) VALUES (?,?,'LOADING') RETURNING id",
                    Long.class, source, quarter);
            Set<String> existing = new HashSet<>(jdbc.queryForList("SELECT area_code || ':' || "
                    + (industry ? "service_code" : "''") + " FROM " + table + " WHERE quarter=?", String.class, quarter));
            Set<String> seen = new HashSet<>();
            int[] counts = new int[3];
            String keys = industry ? "quarter,area_code,service_code" : "quarter,area_code";
            String columns = keys + "," + fields.stream().map(Field::column).collect(Collectors.joining(",")) + ",version_id";
            String updates = fields.stream().map(f -> f.column() + "=EXCLUDED." + f.column()).collect(Collectors.joining(","));
            String sql = "INSERT INTO " + table + " (" + columns + ") VALUES ("
                    + String.join(",", Collections.nCopies((industry ? 3 : 2) + fields.size() + 1, "?"))
                    + ") ON CONFLICT (" + keys + ") DO UPDATE SET " + updates + ",version_id=EXCLUDED.version_id";
            client.pages(service, quarter, page -> {
                context.saveRaw(service + ":" + quarter + ":" + page.start() + "-" + page.end(), page.raw());
                List<Object[]> batch = new ArrayList<>();
                if (page.rows().isArray()) for (JsonNode row : page.rows()) {
                    if (!quarter.equals(row.path("STDR_YYQU_CD").asText())) throw new IllegalArgumentException("응답 분기 불일치");
                    String area = row.path("TRDAR_CD").asText();
                    String code = industry ? row.path("SVC_INDUTY_CD").asText() : "";
                    if (area.isBlank() || (industry && code.isBlank()) || !seen.add(area + ":" + code)) {
                        throw new IllegalArgumentException("누락 또는 중복 분기 키");
                    }
                    counts[0]++;
                    counts[existing.contains(area + ":" + code) ? 2 : 1]++;
                    List<Object> values = new ArrayList<>();
                    values.add(quarter);
                    values.add(area);
                    if (industry) values.add(code);
                    for (Field field : fields) {
                        JsonNode value = row.path(field.external());
                        values.add(exactLong(value, field.external()));
                    }
                    values.add(version);
                    batch.add(values.toArray());
                }
                jdbc.batchUpdate(sql, batch);
            });
            jdbc.update("UPDATE data_versions SET status='READY',row_count=? WHERE id=?", counts[0], version);
            jdbc.update("UPDATE data_versions SET status='READY' WHERE source=? AND period=? AND status='ACTIVE'", source, quarter);
            jdbc.update("UPDATE data_versions SET status='ACTIVE',activated_at=now() WHERE id=?", version);
            return new CollectResult(counts[0], counts[1], counts[2], 0);
        });
    }
}
