package com.seah.startupsim.catalog.store;

import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.StringReader;
import java.util.List;
import java.util.UUID;

/** 고유 UNLOGGED 스테이징으로 COPY한 뒤 공간 좌표를 만들고 본 테이블에 병합한다. */
@Component
public class CopyStoreBulkLoader implements StoreBulkLoader {
    private final JdbcTemplate jdbc;
    public CopyStoreBulkLoader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void load(List<StoreRow> rows, String snapshot, Long versionId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String table = "store_stage_" + UUID.randomUUID().toString().replace("-", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE UNLOGGED TABLE " + table + " (id text,name text,branch text,"
                        + "large text,middle text,small text,small_name text,industry text,district text,"
                        + "dong text,address text,lng double precision,lat double precision,snapshot text,version bigint)");
                try {
                    StringBuilder data = new StringBuilder();
                    for (StoreRow row : rows) {
                        Object[] values = row.parameters(snapshot, versionId);
                        for (int i = 0; i < values.length; i++) {
                            if (i > 0) data.append(',');
                            if (values[i] != null) data.append('"').append(values[i].toString().replace("\"", "\"\"")).append('"');
                        }
                        data.append('\n');
                    }
                    connection.unwrap(PGConnection.class).getCopyAPI().copyIn(
                            "COPY " + table + " FROM STDIN WITH (FORMAT csv)", new StringReader(data.toString()));
                    statement.execute("INSERT INTO stores(store_id,name,branch_name,large_code,middle_code,small_code,small_name,std_industry_code,district_code,dong_code,road_address,geom,snapshot,version_id) SELECT id,name,branch,large,middle,small,small_name,industry,"
                            + "district,dong,address,ST_SetSRID(ST_MakePoint(lng,lat),4326),snapshot,version FROM "
                            + table + " WHERE true " + JdbcStoreBulkLoader.UPSERT.substring(JdbcStoreBulkLoader.UPSERT.indexOf("ON CONFLICT")));
                } catch (java.io.IOException e) {
                    throw new java.sql.SQLException("COPY 입력 실패", e);
                } finally {
                    statement.execute("DROP TABLE " + table);
                }
            }
            return null;
        });
    }
}

