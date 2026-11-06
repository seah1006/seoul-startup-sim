package com.seah.startupsim;

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
    long user;
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE users,trade_areas,data_versions CASCADE");
        user=((Number)auth.create().get("userId")).longValue();
        jdbc.update("INSERT INTO data_versions(id,source,status) VALUES (100,'fixture','ACTIVE')");
        jdbc.update("INSERT INTO trade_areas(area_code,name,district_code,geom,centroid,version_id,area_m2) VALUES ('A','테스트 상권','11440',ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),ST_SetSRID(ST_MakePoint(127,38),4326),100,1000000),('B','이웃 상권','11440',ST_Multi(ST_GeomFromText('POLYGON((128 37,129 37,129 39,128 39,128 37))',4326)),ST_SetSRID(ST_MakePoint(128.5,38),4326),100,1000000)");
        for(String q:List.of("20231","20232","20233","20234","20241")) {
            jdbc.update("INSERT INTO area_sales_q(quarter,area_code,service_code,sales_amount,version_id) VALUES (?,'A','CS100010',1000000000,100),(?,'B','CS100010',500000000,100)",q,q);
            jdbc.update("INSERT INTO area_stores_q(quarter,area_code,service_code,store_count,opening_count,closing_count,version_id) VALUES (?,'A','CS100010',10,2,1,100),(?,'B','CS100010',5,1,0,100)",q,q);
        }
        jdbc.update("INSERT INTO stores(store_id,name,small_code,geom,snapshot,version_id) VALUES ('S','실제 사실표시용','I21201',ST_SetSRID(ST_MakePoint(127,38),4326),'20231',100)");
    }
    long game() {
        long id=((Number)service.create(user,new GameTypes.Create("테스트",GameTypes.Background.OFFICE_WORKER,"c1",123,List.of("service"),"20231")).get("gameId")).longValue();
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
            assertEquals(ar.get("result"),br.get("result"));
        }
        assertEquals(jdbc.queryForObject("SELECT cash FROM games WHERE id=?",Long.class,a),jdbc.queryForObject("SELECT initial_capital+(SELECT sum(amount) FROM ledger_entries WHERE game_id=?) FROM games WHERE id=?",Long.class,a,a));
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
        long id=game();long other=((Number)auth.create().get("userId")).longValue();
        assertThrows(GameException.class,()->repository.state(id,other));
        assertThrows(GameException.class,()->auth.resolve("invalid"));
        assertThrows(GameException.class,()->auth.resolve(null));
    }
}
