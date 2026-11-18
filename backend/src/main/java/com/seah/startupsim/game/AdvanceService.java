package com.seah.startupsim.game;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Price;
import com.seah.startupsim.game.GameTypes.Activity;
import org.springframework.context.ApplicationEventPublisher;
import com.seah.startupsim.game.GameTypes.Costs;
import com.seah.startupsim.game.GameTypes.Shop;
import com.seah.startupsim.game.GameTypes.Advance;
import static com.seah.startupsim.game.GameRepository.number;

/** 게임 행을 잠근 한 트랜잭션 안에서 결산·원장·멱등 응답을 함께 저장한다. */
@Service
public class AdvanceService {
    private final GameRepository r;
    private final QuarterCalculator calculator;
    private final TurnEventHook eventHook;
    private final ApplicationEventPublisher events;
    private final TurnRepository turns;
    private final LedgerRepository ledger;

    public AdvanceService(GameRepository r, QuarterCalculator calculator,
            TurnEventHook eventHook, ApplicationEventPublisher events,
            TurnRepository turns, LedgerRepository ledger) {
        this.r = r;
        this.calculator = calculator;
        this.eventHook = eventHook;
        this.events = events;
        this.turns = turns;
        this.ledger = ledger;
    }
    @Transactional
    public Map<String,Object> advance(long id,long user,String key,long expectedVersion) {
        if(key==null || key.isBlank() || key.length()>100) throw GameException.invalid("Idempotency-Key가 필요합니다.");
        var g=r.game(id,user,true);
        var cached=r.jdbc().queryForList("SELECT response::text FROM idempotency_keys WHERE game_id=? AND key=?",String.class,id,key);
        if(!cached.isEmpty()) return r.decode(cached.getFirst());
        if(number(g,"version")!=expectedVersion) throw new GameException(409,"GAME_VERSION_CONFLICT","게임 버전이 변경되었습니다.");
        GameService.playing(g);
        int turn=(int)number(g,"current_turn");
        String quarter=GameRepository.quarter(g.get("start_quarter").toString(),turn);
        var decisions=r.jdbc().queryForList("SELECT * FROM turn_decisions WHERE game_id=? AND turn=?",id,turn);
        if(decisions.isEmpty()) throw GameException.invalid("이번 분기의 운영 결정을 저장해 주세요.");
        var shops=r.jdbc().queryForList("SELECT * FROM shops WHERE game_id=?",id);
        if(shops.isEmpty()) throw GameException.invalid("먼저 창업해 주세요.");
        var shop=shops.getFirst();var d=decisions.getFirst();
        Decision decision=new Decision(Price.valueOf(d.get("price_level").toString()),(int)number(d,"open_hours"),(int)number(d,"staff"),number(d,"marketing"),Activity.valueOf(d.get("owner_activity").toString()));
        var sales=r.jdbc().queryForList("SELECT q.sales_amount FROM area_sales_q q JOIN game_data_pins p ON p.version_id=q.version_id AND p.game_id=? WHERE q.area_code=? AND q.service_code=? AND q.quarter=?",Long.class,id,shop.get("area_code"),shop.get("service_code"),quarter);
        var counts=r.jdbc().queryForList("SELECT q.* FROM area_stores_q q JOIN game_data_pins p ON p.version_id=q.version_id AND p.game_id=? WHERE q.area_code=? AND q.service_code=? AND q.quarter=?",id,shop.get("area_code"),shop.get("service_code"),quarter);
        Map<String,Object> count=counts.isEmpty()?Map.of():counts.getFirst();
        Long average=r.jdbc().queryForObject("SELECT (sum(s.sales_amount)/nullif(sum(t.store_count),0))::bigint FROM area_sales_q s JOIN area_stores_q t USING(area_code,quarter,service_code) JOIN trade_areas a ON a.area_code=s.area_code WHERE a.district_code=? AND s.quarter=? AND s.service_code=? AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=s.version_id) AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=t.version_id) AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=a.version_id)",Long.class,shop.get("district_code"),quarter,shop.get("service_code"),id,id,id);
        Long market=sales.isEmpty()?null:sales.getFirst();
        if(market==null && average==null) throw GameException.invalid("고정된 버전에 이 분기의 매출 추정 자료가 없습니다.");
        Map<String,Double> settings=new LinkedHashMap<>();
        r.decode(g.get("settings").toString()).forEach((k,v)->settings.put(k,((Number)v).doubleValue()));
        var input=new QuarterCalculator.Input(number(g,"seed"),turn,quarter,number(g,"cash"),market,average==null?0:average,
                (int)number(count,"store_count"),r.jdbc().queryForObject("SELECT count(*) FROM rivals WHERE game_id=?",Integer.class,id),
                shop.get("area_m2")==null?1:((Number)shop.get("area_m2")).doubleValue(),number(count,"opening_count"),number(count,"closing_count"),r.stats(id),r.focus(id),decision,settings,(String)shop.get("district_code"));
        QuarterOutcome result=calculator.calculate(input);
        long marketSize=market==null?input.districtPerStore()*input.stores():market;
        turns.saveOutcome(id, marketSize, result);
        ledger.recordQuarter(id, result);
        for(var c:result.statChanges()) {
            if(!OwnerCandidates.STATS.contains(c.stat())) throw new IllegalStateException();
            r.jdbc().update("UPDATE owners SET "+c.stat()+"=? WHERE game_id=?",c.after(),id);
        }
        int negatives=result.cashAfter()<0?(int)number(g,"negative_quarters")+1:0;
        String ending=negatives>=2?"CLOSED":quarter.equals(g.get("last_quarter"))?"LONG_RUNNING":null;
        r.jdbc().update("UPDATE games SET current_turn=current_turn+1,version=version+1,cash=?,negative_quarters=?,status=?,ending_type=? WHERE id=?",result.cashAfter(),negatives,ending==null?"PLAYING":"ENDED",ending,id);
        TurnResultBuilder resultBuilder = new TurnResultBuilder(result);
        eventHook.afterQuarter(new GameContext(id, user, number(g, "seed"), turn, quarter, ending),
                result, resultBuilder);
        result = resultBuilder.build();
        turns.saveEventPresentation(id, result);
        if (ending != null) {
            events.publishEvent(new GameEndedEvent(id, ending));
        }
        var gameState = r.state(id, user);
        gameState.put("pendingTurningPoint", resultBuilder.pendingTurningPoint());
        Map<String,Object> response=Map.of("result",result,"game",gameState);
        String encoded=r.encode(response);
        r.jdbc().update("INSERT INTO idempotency_keys(game_id,key,response) VALUES (?,?,?::jsonb)",id,key,encoded);
        return r.decode(encoded);
    }
}
