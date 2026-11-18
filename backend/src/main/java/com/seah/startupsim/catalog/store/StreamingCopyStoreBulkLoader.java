package com.seah.startupsim.catalog.store;

import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 파일 전체를 제한된 파이프로 COPY하고 한 번의 집합 연산으로 병합한다. */
@Component
public class StreamingCopyStoreBulkLoader {
    private final JdbcTemplate jdbc;
    private final StoreCsvParser parser;

    public StreamingCopyStoreBulkLoader(JdbcTemplate jdbc, StoreCsvParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    public StoreCsvParser.Counts load(Path file, int limit, String snapshot, Long version) {
        return load(file, limit, snapshot, version, false);
    }

    public StoreCsvParser.Counts load(Path file, int limit, String snapshot, Long version, boolean replace) {
        return jdbc.execute((ConnectionCallback<StoreCsvParser.Counts>) connection -> {
            String table = "store_stream_" + UUID.randomUUID().toString().replace("-", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE " + table + " (id text,name text,branch text,"
                        + "large text,middle text,small text,small_name text,industry text,district text,"
                        + "dong text,address text,lng double precision,lat double precision,snapshot text,version bigint)");
                try (PipedReader reader = new PipedReader(65536); PipedWriter writer = new PipedWriter(reader)) {
                    AtomicReference<StoreCsvParser.Counts> counts = new AtomicReference<>();
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    Thread producer = Thread.ofVirtual().start(() -> {
                        try (writer) {
                            counts.set(parser.read(file, limit, rows -> {
                                try {
                                    for (StoreRow row : rows) {
                                        Object[] values = row.parameters(snapshot, version);
                                        for (int n = 0; n < values.length; n++) {
                                            if (n > 0) writer.write(',');
                                            if (values[n] != null) {
                                                writer.write('"');
                                                writer.write(values[n].toString().replace("\"", "\"\""));
                                                writer.write('"');
                                            }
                                        }
                                        writer.write('\n');
                                    }
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }));
                        } catch (Throwable e) {
                            failure.set(e);
                        }
                    });
                    try {
                        connection.unwrap(PGConnection.class).getCopyAPI().copyIn(
                                "COPY " + table + " FROM STDIN WITH (FORMAT csv)", reader);
                    } finally {
                        reader.close();
                        producer.join();
                    }
                    if (failure.get() != null) {
                        throw new SQLException("CSV 스트림 실패", failure.get());
                    }
                    statement.execute("ANALYZE " + table);
                    statement.execute("INSERT INTO stores(store_id,name,branch_name,large_code,middle_code,small_code,small_name,std_industry_code,district_code,dong_code,road_address,geom,snapshot,version_id) "
                            + "SELECT id,name,branch,large,middle,small,small_name,industry,district,dong,address,ST_SetSRID(ST_MakePoint(lng,lat),4326),snapshot,version FROM "
                            + table + " WHERE true " + JdbcStoreBulkLoader.UPSERT.substring(JdbcStoreBulkLoader.UPSERT.indexOf("ON CONFLICT")));
                    if (replace) {
                        statement.execute("DELETE FROM stores s WHERE NOT EXISTS (SELECT 1 FROM " + table + " n WHERE n.id=s.store_id)");
                    }
                    return counts.get();
                } catch (IOException e) {
                    throw new SQLException("COPY 입력 실패", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("COPY 중단", e);
                } finally {
                    statement.execute("DROP TABLE " + table);
                }
            }
        });
    }
}
