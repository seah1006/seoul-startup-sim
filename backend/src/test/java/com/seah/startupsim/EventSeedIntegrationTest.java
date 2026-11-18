package com.seah.startupsim;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 실제 PostgreSQL에서 V8 스키마와 생성된 콘텐츠의 제약·수를 확인한다. */
@Testcontainers
class EventSeedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @Test
    void migrationLoadsAllDefinitionsAndOptions() throws Exception {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT (SELECT count(*) FROM turning_points),
                               (SELECT count(*) FROM turning_point_options),
                               (SELECT count(*) FROM reaction_templates),
                               (SELECT count(*) FROM moment_templates),
                               (SELECT count(*) FROM turning_points p
                                WHERE (SELECT count(*) FROM turning_point_options o
                                       WHERE o.turning_point_id = p.id) NOT BETWEEN 2 AND 3),
                               (SELECT count(*) FROM turning_points WHERE jsonb_typeof(stat_bonus) <> 'array')
                        """)) {
            result.next();
            assertEquals(12, result.getInt(1));
            assertEquals(28, result.getInt(2));
            assertEquals(36, result.getInt(3));
            assertEquals(12, result.getInt(4));
            assertEquals(0, result.getInt(5));
            assertEquals(0, result.getInt(6));
        }
    }
}
