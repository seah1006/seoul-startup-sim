package com.seah.startupsim;

import com.seah.startupsim.ingest.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestIntegrationTest {
 @Container
 static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
 @DynamicPropertySource
 static void database(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",DB::getJdbcUrl);
  r.add("spring.datasource.username",DB::getUsername);
  r.add("spring.datasource.password",DB::getPassword);
 }
 @Autowired IngestService service;
 @Autowired JdbcTemplate jdbc;
 @Autowired FakeCollector fake;
 @Autowired Environment environment;
 @Autowired PlatformTransactionManager transactions;
 @BeforeEach void reset(){
  jdbc.execute("TRUNCATE raw_payloads,ingest_runs,data_versions RESTART IDENTITY CASCADE");
  fake.action=ctx->new CollectResult(0,0,0,0);
 }
 @Test void migrationAndPostgis(){
  assertNotNull(jdbc.queryForObject("SELECT postgis_version()",String.class));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success=true",Integer.class));
 }
 @Test void successSavesCountsRawAndChecksum() throws Exception {
  String payload="{\"name\":\"서울\"}";
  fake.action=ctx->{assertEquals("2026Q1",ctx.targetPeriod());ctx.saveRaw("page-1",payload);return new CollectResult(4,2,1,1);};
  long id=service.run("fake","2026Q1");
  var row=jdbc.queryForMap("SELECT * FROM ingest_runs WHERE id=?",id);
  assertEquals("SUCCEEDED",row.get("status"));
  assertEquals(4,row.get("fetched"));assertEquals(2,row.get("inserted"));
  assertEquals(1,row.get("updated"));assertEquals(1,row.get("failed"));assertNotNull(row.get("finished_at"));
  assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))),jdbc.queryForObject("SELECT checksum FROM raw_payloads WHERE run_id=?",String.class,id));
 }
 @Test void failureSurvivesCallerRollback(){
  fake.action=ctx->{ctx.saveRaw("page-1","{}");throw new IllegalStateException("simulated failure");};
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->service.run("fake",null)));
  var row=jdbc.queryForMap("SELECT * FROM ingest_runs");
  assertEquals("FAILED",row.get("status"));assertNotNull(row.get("finished_at"));
  assertTrue(row.get("error_message").toString().contains("IllegalStateException"));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM raw_payloads",Integer.class));
 }
 @Test void concurrentSourceRunsOnlyOnce() throws Exception {
  CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
  fake.action=ctx->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return new CollectResult(1,1,0,0);};
  try(var executor=Executors.newSingleThreadExecutor()){
   Future<Long> first=executor.submit(()->service.run("fake",null));
   try {assertTrue(entered.await(10,TimeUnit.SECONDS));assertThrows(IngestAlreadyRunningException.class,()->service.run("fake",null));}
   finally {release.countDown();}
   first.get(10,TimeUnit.SECONDS);
  }
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ingest_runs",Integer.class));
 }
 @Test void onlyOneActiveVersion(){
  jdbc.update("INSERT INTO data_versions(source,status) VALUES ('test','ACTIVE')");
  assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO data_versions(source,status) VALUES ('test','ACTIVE')"));
 }
 @Test void healthEndpoint() throws Exception {
  var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+environment.getProperty("local.server.port")+"/api/health/db")).GET().build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(200,response.statusCode());assertTrue(response.body().contains("\"status\":\"UP\""));assertTrue(response.body().contains("postgisVersion"));
 }
 @Test void unknownSourceDoesNotCreateRun(){
  assertThrows(UnknownSourceException.class,()->service.run("missing",null));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingest_runs",Integer.class));
 }
 @Test void duplicateRawRequestIsRejected(){
  fake.action=ctx->{ctx.saveRaw("page","{}");ctx.saveRaw("page","{}");return new CollectResult(0,0,0,0);};
  assertThrows(DataIntegrityViolationException.class,()->service.run("fake",null));
  assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM ingest_runs",String.class));
 }
 static class FakeCollector implements Collector {
  volatile Function<IngestContext,CollectResult> action;
  public String source(){return "fake";}
  public CollectResult collect(IngestContext ctx){return action.apply(ctx);}
 }
 @TestConfiguration
 static class Config {
  @Bean FakeCollector fakeCollector(){return new FakeCollector();}
 }
}