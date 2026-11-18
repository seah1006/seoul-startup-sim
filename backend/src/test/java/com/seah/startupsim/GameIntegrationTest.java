package com.seah.startupsim;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.test.context.event.ApplicationEvents;

import org.springframework.test.context.event.RecordApplicationEvents;

import com.seah.startupsim.game.TurnResultBuilder;

import com.seah.startupsim.game.TurnEventHook;

import com.seah.startupsim.game.GameEndedEvent;

import com.seah.startupsim.game.AdvanceService;
import com.seah.startupsim.game.GameException;
import com.seah.startupsim.game.GameRepository;
import com.seah.startupsim.game.GameService;
import com.seah.startupsim.game.GuestAuth;
import com.seah.startupsim.game.GameTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
@RecordApplicationEvents
class GameIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired GuestAuth auth;
    @Autowired GameService service;
    @Autowired AdvanceService advance;
    @Autowired GameRepository repository;
    @MockitoBean
    TurnEventHook eventHook;
    long user;
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE users,trade_areas,data_versions CASCADE");
        user=auth.create().userId();
        jdbc.update("INSERT INTO data_versions(id,source,status) VALUES (100,'fixture','ACTIVE')");
        jdbc.update("INSERT INTO trade_areas(area_code,name,district_code,geom,centroid,version_id,area_m2) VALUES ('A','테스트 상권','11440',ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),ST_SetSRID(ST_MakePoint(127,38),4326),100,1000000),('B','이웃 상권','11440',ST_Multi(ST_GeomFromText('POLYGON((128 37,129 37,129 39,128 39,128 37))',4326)),ST_SetSRID(ST_MakePoint(128.5,38),4326),100,1000000)");
        for(String q:List.of("20231","20232","20233","20234","20241")) {
            jdbc.update("INSERT INTO area_sales_q(quarter,area_code,service_code,sales_amount,version_id) VALUES (?,'A','CS100010',1000000000,100),(?,'B','CS100010',500000000,100)",q,q);
            jdbc.update("INSERT INTO area_stores_q(quarter,area_code,service_code,store_count,opening_count,closing_count,version_id) VALUES (?,'A','CS100010',10,2,1,100),(?,'B','CS100010',5,1,0,100)",q,q);
        }
        jdbc.update("INSERT INTO stores(store_id,name,small_code,geom,snapshot,version_id) VALUES ('S','실제 사실표시용','I21201',ST_SetSRID(ST_MakePoint(127,38),4326),'20231',100)");
    }
    long game() {
        long id=service.create(user,new GameTypes.Create("테스트",GameTypes.Background.OFFICE_WORKER,"c1",123,List.of("service"),"20231")).gameId();
        service.shop(id,user,new GameTypes.Shop("A","CS100010",127,38,"가상 가게"));return id;
    }
    void decision(long id,int turn) { service.decision(id,user,turn,new GameTypes.Decision(GameTypes.Price.NORMAL,10,2,1000000,GameTypes.Activity.MENU_DEV)); }
    @Test @DisplayName("같은 시드의 두 게임은 4분기 결과가 같고 원장과 현금이 일치한다")
    void reproducible() {
        long a=game(),b=game();
        for(int turn=0;turn<4;turn++) {
            decision(a,turn);decision(b,turn);
            var ar=advance.advance(a,user,UUID.randomUUID().toString(),turn+1);
            var br=advance.advance(b,user,UUID.randomUUID().toString(),turn+1);
            assertEquals(ar.result(),br.result());
        }
        assertEquals(jdbc.queryForObject("SELECT cash FROM games WHERE id=?",Long.class,a),jdbc.queryForObject("SELECT initial_capital+(SELECT sum(amount) FROM ledger_entries WHERE game_id=?) FROM games WHERE id=?",Long.class,a,a));
    }
    @Autowired ObjectMapper json;
    @Autowired ApplicationEvents applicationEvents;
    @Test @DisplayName("고정 시드 4분기 JSON 회귀 스냅샷")
    void snapshot() throws Exception {
        long id=game();
        var results=new ArrayList<Object>();
        for(int turn=0;turn<4;turn++) {
            decision(id,turn);
            results.add(advance.advance(id,user,"snapshot-"+turn,turn+1).result());
        }
        byte[] actual = json.writeValueAsString(results).getBytes(StandardCharsets.UTF_8);
        try (var golden = getClass().getResourceAsStream("/game/four-quarters.json")) {
            assertNotNull(golden);
            assertArrayEquals(golden.readAllBytes(), actual);
        }
    }
    @Test
    @DisplayName("확장 훅이 채운 표현은 응답과 결산 이력에 함께 저장된다")
    void hookPresentation() {
        doAnswer(invocation -> {
            TurnResultBuilder builder = invocation.getArgument(2);
            builder.moment("가상 순간");
            builder.reactions(List.of("가상 반응"));
            builder.pendingTurningPoint(true);
            return null;
        }).when(eventHook).afterQuarter(any(),
                any(), any());
        long id = game();
        decision(id, 0);
        var response = advance.advance(id, user, "hook", 1);
        var result = response.result();
        assertEquals("가상 순간", result.moment());
        assertEquals(List.of("가상 반응"), result.reactions());
        assertEquals(true, response.game().pendingTurningPoint());
        assertEquals(result, repository.turns(id, user).getFirst());
        assertEquals("[\"가상 반응\"]", jdbc.queryForObject(
                "SELECT reactions::text FROM turn_results WHERE game_id=?", String.class, id));
    }

    @Test
    @DisplayName("확장 훅 실패는 결산·원장·게임 버전을 모두 롤백한다")
    void hookFailureRollsBack() {
        doThrow(new IllegalStateException("hook failure"))
                .when(eventHook).afterQuarter(any(),
                        any(), any());
        long id = game();
        decision(id, 0);
        assertThrows(IllegalStateException.class, () -> advance.advance(id, user, "failure", 1));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM turn_results WHERE game_id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE game_id=?", Integer.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM games WHERE id=?", Long.class, id));
        assertEquals(0L, applicationEvents.stream(GameEndedEvent.class).count());
    }

    @Test
    @DisplayName("마지막 분기에 종료 이벤트는 한 번만 발행하고 기본 전환점은 없다")
    void endingEvent() {
        long id = game();
        for (int turn = 0; turn < 5; turn++) {
            decision(id, turn);
            advance.advance(id, user, "ending-" + turn, turn + 1);
        }
        advance.advance(id, user, "ending-4", 5);
        var events = applicationEvents.stream(GameEndedEvent.class).toList();
        assertEquals(1, events.size());
        assertEquals(id, events.getFirst().gameId());
        assertEquals("LONG_RUNNING", events.getFirst().endingType());
        assertEquals(false, repository.state(id, user).pendingTurningPoint());
    }

    @Test @DisplayName("멱등 키 재시도와 DB 유일 제약이 이중 결산을 막는다")
    void idempotency() {
        long id=game();decision(id,0);
        var first=advance.advance(id,user,"once",1);
        assertEquals(first,advance.advance(id,user,"once",1));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM turn_results WHERE game_id=?",Integer.class,id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO turn_results SELECT * FROM turn_results WHERE game_id=?",id));
    }
    @Test @DisplayName("같은 버전의 동시 진행은 하나만 성공한다")
    void concurrency() throws Exception {
        long id=game();decision(id,0);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> call=()->{gate.await();try {advance.advance(id,user,UUID.randomUUID().toString(),1);return 200;}catch(GameException e){return 409;}};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(),b.get()).sorted().toList());
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM turn_results WHERE game_id=?",Integer.class,id));
    }
    @Test @DisplayName("다른 게스트의 조회와 잘못된 토큰은 거절한다")
    void ownership() {
        long id=game();long other=auth.create().userId();
        assertThrows(GameException.class,()->repository.state(id,other));
        assertThrows(GameException.class,()->auth.resolve("invalid"));
        assertThrows(GameException.class,()->auth.resolve(null));
    }
}

