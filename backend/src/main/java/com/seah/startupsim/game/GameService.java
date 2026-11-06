package com.seah.startupsim.game;
import com.seah.startupsim.catalog.IndustryMappingPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Create;
import com.seah.startupsim.game.GameTypes.Shop;
import com.seah.startupsim.game.GameTypes.Background;
import static com.seah.startupsim.game.GameRepository.number;

/** 소유권과 게임 행 잠금을 경계로 생성·창업·결정 저장을 처리한다. */
@Service
public class GameService {
    private final GameRepository r;
    private final OwnerCandidates candidates;
    private final IndustryMappingPort mapping;
    public GameService(GameRepository r,OwnerCandidates candidates,IndustryMappingPort mapping) { this.r=r;this.candidates=candidates;this.mapping=mapping; }
    @Transactional
    public Map<String,Object> create(long user,Create request) {
        if(request.focusStats().stream().distinct().count()!=request.focusStats().size() || !OwnerCandidates.STATS.containsAll(request.focusStats())) throw GameException.invalid("집중 능력치를 확인해 주세요.");
        var candidate=candidates.generate(request.background(),request.candidateSeed()).stream().filter(c->c.candidateId().equals(request.candidateId())).findFirst().orElseThrow(()->GameException.invalid("사장님 후보가 없습니다."));
        var quarters=r.jdbc().queryForList("SELECT DISTINCT q.quarter FROM area_sales_q q JOIN data_versions v ON v.id=q.version_id WHERE v.status='ACTIVE' ORDER BY q.quarter",String.class);
        if(quarters.isEmpty()) throw GameException.invalid("활성 매출 데이터가 없습니다.");
        String start=request.startQuarter()==null?quarters.getFirst():request.startQuarter();
        if(!quarters.contains(start)) throw GameException.invalid("시작 분기 데이터가 없습니다.");
        Map<String,Double> settings=new LinkedHashMap<>();
        r.jdbc().query("SELECT key,value::text FROM game_settings",rs->{ settings.put(rs.getString(1),Double.parseDouble(rs.getString(2))); });
        long capital=settings.get("initialCapital").longValue();
        Long id=r.jdbc().queryForObject("INSERT INTO games(user_id,seed,start_quarter,last_quarter,cash,initial_capital,settings) VALUES (?,?,?,?,?,?,?::jsonb) RETURNING id",Long.class,user,request.candidateSeed(),start,quarters.getLast(),capital,capital,r.encode(settings));
        r.jdbc().update("INSERT INTO game_data_pins SELECT ?,source,id FROM data_versions WHERE status='ACTIVE'",id);
        var s=candidate.stats();var f=request.focusStats();
        r.jdbc().update("INSERT INTO owners VALUES (?,?,?,?,?,?,?,?,?,?,?)",id,request.ownerName(),request.background().name(),s.get("cooking"),s.get("service"),s.get("management"),s.get("marketing"),s.get("stamina"),s.get("network"),f.isEmpty()?null:f.getFirst(),f.size()<2?null:f.get(1));
        return r.state(id,user);
    }
    @Transactional
    public Map<String,Object> shop(long id,long user,Shop request) {
        var g=r.game(id,user,true); playing(g);
        if(r.jdbc().queryForObject("SELECT count(*) FROM shops WHERE game_id=?",Integer.class,id)>0) throw GameException.invalid("이미 창업한 게임입니다.");
        var areas=r.jdbc().queryForList("SELECT a.* FROM trade_areas a WHERE area_code=? AND ST_Contains(geom,ST_SetSRID(ST_MakePoint(?,?),4326)) AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=a.version_id)",request.areaCode(),request.lng(),request.lat(),id);
        if(areas.isEmpty()) throw GameException.invalid("고정된 상권 영역 안의 위치를 선택해 주세요.");
        var a=areas.getFirst();
        var industries=r.jdbc().queryForList("SELECT * FROM service_industries WHERE service_code=? AND category='FOOD' AND has_sales",request.serviceCode());
        if(industries.isEmpty()) throw GameException.invalid("창업 가능한 외식 업종을 선택해 주세요.");
        if(r.jdbc().queryForObject("SELECT count(*) FROM area_sales_q q JOIN game_data_pins p ON p.version_id=q.version_id AND p.game_id=? WHERE area_code=?",Integer.class,id,request.areaCode())==0) throw GameException.invalid("매출 자료가 없는 상권입니다.");
        r.jdbc().update("INSERT INTO shops(game_id,area_code,area_name,district_code,service_code,service_name,name,geom,capital,area_m2) VALUES (?,?,?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?)",id,request.areaCode(),a.get("name"),a.get("district_code"),request.serviceCode(),industries.getFirst().get("name"),request.shopName(),request.lng(),request.lat(),g.get("initial_capital"),a.get("area_m2"));
        for(String code:mapping.smallCodes(request.serviceCode())) {
            r.jdbc().update("INSERT INTO rivals(game_id,sbiz_store_id,service_code,geom) SELECT ?,s.store_id,?,s.geom FROM stores s WHERE s.small_code=? AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=s.version_id) AND ST_DWithin(s.geom::geography,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,300)",id,request.serviceCode(),code,id,request.lng(),request.lat());
        }
        r.jdbc().update("UPDATE games SET version=version+1 WHERE id=?",id);
        return r.state(id,user);
    }
    @Transactional
    public Map<String,Object> decision(long id,long user,int turn,Decision d) {
        var g=r.game(id,user,true); playing(g);
        if(turn<number(g,"current_turn")) throw new GameException(409,"TURN_ALREADY_ADVANCED","이미 진행한 분기입니다.");
        if(turn!=number(g,"current_turn")) throw GameException.invalid("현재 분기의 결정만 저장할 수 있습니다.");
        if(d.marketingBudget()>1000000000000L) throw GameException.invalid("마케팅 예산이 너무 큽니다.");
        r.jdbc().update("INSERT INTO turn_decisions VALUES (?,?,?,?,?,?,?) ON CONFLICT(game_id,turn) DO UPDATE SET price_level=excluded.price_level,open_hours=excluded.open_hours,staff=excluded.staff,marketing=excluded.marketing,owner_activity=excluded.owner_activity",id,turn,d.priceLevel().name(),d.openHours(),d.staff(),d.marketingBudget(),d.ownerActivity().name());
        return Map.of("turn",turn,"saved",true);
    }
    static void playing(Map<String,Object> game) {
        if(!"PLAYING".equals(game.get("status"))) throw new GameException(409,"GAME_ENDED","종료된 게임입니다.");
    }
}
