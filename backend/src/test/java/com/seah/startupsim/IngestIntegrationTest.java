package com.seah.startupsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seah.startupsim.ingest.CollectResult;
import com.seah.startupsim.ingest.Collector;
import com.seah.startupsim.ingest.IngestAlreadyRunningException;
import com.seah.startupsim.ingest.IngestContext;
import com.seah.startupsim.ingest.IngestService;
import com.seah.startupsim.ingest.UnknownSourceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.ingest.stale-after=30m")
class IngestIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired IngestService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeCollector fake;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager transactions;

    @Autowired ConfigurableApplicationContext context;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE raw_payloads,ingest_runs,data_versions RESTART IDENTITY CASCADE");
        fake.action = ctx -> new CollectResult(0, 0, 0, 0);
    }

    @Test
    @DisplayName("Flyway 마이그레이션과 PostGIS 확장이 적용된다")
    void migrationAndPostgis() {
        assertNotNull(jdbc.queryForObject("SELECT postgis_version()", String.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success=true",
                        Integer.class));
    }

    @Test
    @DisplayName("수집 성공 시 건수와 원본 및 체크섬을 저장한다")
    void successSavesCountsRawAndChecksum() throws Exception {
        String payload = "{\"name\":\"서울\"}";
        fake.action =
                ctx -> {
                    assertEquals("2026Q1", ctx.targetPeriod());
                    ctx.saveRaw("page-1", payload);
                    return new CollectResult(4, 2, 1, 1);
                };
        long id = service.run("fake", "2026Q1");
        var row = jdbc.queryForMap("SELECT * FROM ingest_runs WHERE id=?", id);
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals(4, row.get("fetched"));
        assertEquals(2, row.get("inserted"));
        assertEquals(1, row.get("updated"));
        assertEquals(1, row.get("failed"));
        assertNotNull(row.get("finished_at"));
        assertEquals(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(payload.getBytes(StandardCharsets.UTF_8))),
                jdbc.queryForObject(
                        "SELECT checksum FROM raw_payloads WHERE run_id=?", String.class, id));
    }

    @Test
    @DisplayName("호출자 롤백에도 실패 이력과 원본이 남는다")
    void failureSurvivesCallerRollback() {
        fake.action =
                ctx -> {
                    ctx.saveRaw("page-1", "{}");
                    throw new IllegalStateException("simulated failure");
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(status -> service.run("fake", null)));
        var row = jdbc.queryForMap("SELECT * FROM ingest_runs");
        assertEquals("FAILED", row.get("status"));
        assertNotNull(row.get("finished_at"));
        assertTrue(row.get("error_message").toString().contains("IllegalStateException"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM raw_payloads", Integer.class));
    }

    @Test
    @DisplayName("같은 출처의 동시 수집은 하나만 실행된다")
    void concurrentSourceRunsOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fake.action =
                ctx -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return new CollectResult(1, 1, 0, 0);
                };
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Long> first = executor.submit(() -> service.run("fake", null));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(IngestAlreadyRunningException.class, () -> service.run("fake", null));
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ingest_runs", Integer.class));
    }

    @Test
    @DisplayName("같은 출처의 활성 버전은 하나만 허용한다")
    void onlyOneActiveVersion() {
        jdbc.update("INSERT INTO data_versions(source,status) VALUES ('test','ACTIVE')");
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO data_versions(source,status) VALUES"
                                        + " ('test','ACTIVE')"));
    }

    @Test
    @DisplayName("DB 헬스 API가 연결 상태와 PostGIS 버전을 반환한다")
    void healthEndpoint() throws Exception {
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://localhost:"
                                                                + environment.getProperty(
                                                                        "local.server.port")
                                                                + "/api/health/db"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
        assertTrue(response.body().contains("postgisVersion"));
    }

    @Test
    @DisplayName("미등록 출처는 실행 이력을 만들지 않고 거절한다")
    void unknownSourceDoesNotCreateRun() {
        assertThrows(UnknownSourceException.class, () -> service.run("missing", null));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ingest_runs", Integer.class));
    }

    @Test
    @DisplayName("같은 실행의 중복 원본 요청을 거절한다")
    void duplicateRawRequestIsRejected() {
        fake.action =
                ctx -> {
                    ctx.saveRaw("page", "{}");
                    ctx.saveRaw("page", "{}");
                    return new CollectResult(0, 0, 0, 0);
                };
        assertThrows(DataIntegrityViolationException.class, () -> service.run("fake", null));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ingest_runs", String.class));
    }

    @Test
    @DisplayName("앱 시작 시 설정 기준보다 오래된 RUNNING만 실패 처리하고 다시 수집할 수 있다")
    void applicationReadyCleansOnlyStaleRunningRuns() {
        jdbc.update(
                """
                INSERT INTO ingest_runs(source, status, started_at)
                VALUES ('fake', 'RUNNING', now() - interval '40 minutes'),
                       ('recent', 'RUNNING', now() - interval '10 minutes'),
                       ('completed', 'SUCCEEDED', now() - interval '3 hours')
                """);

        context.publishEvent(
                new ApplicationReadyEvent(
                        new SpringApplication(StartupSimApplication.class),
                        new String[0],
                        context,
                        Duration.ZERO));

        var stale = jdbc.queryForMap("SELECT * FROM ingest_runs WHERE source = 'fake'");
        assertEquals("FAILED", stale.get("status"));
        assertEquals("중단됨: 앱 재시작 시 정리", stale.get("error_message"));
        assertNotNull(stale.get("finished_at"));
        assertEquals(
                "RUNNING",
                jdbc.queryForObject(
                        "SELECT status FROM ingest_runs WHERE source = 'recent'", String.class));
        assertEquals(
                "SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT status FROM ingest_runs WHERE source = 'completed'", String.class));

        long nextRunId = service.run("fake", null);
        assertEquals(
                "SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT status FROM ingest_runs WHERE id = ?", String.class, nextRunId));
    }

    static class FakeCollector implements Collector {
        volatile Function<IngestContext, CollectResult> action;

        public String source() {
            return "fake";
        }

        public CollectResult collect(IngestContext ctx) {
            return action.apply(ctx);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeCollector fakeCollector() {
            return new FakeCollector();
        }
    }
}
