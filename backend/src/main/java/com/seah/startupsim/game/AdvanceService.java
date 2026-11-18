package com.seah.startupsim.game;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Price;
import com.seah.startupsim.game.GameTypes.Activity;
import org.springframework.context.ApplicationEventPublisher;
import com.seah.startupsim.game.GameTypes.Costs;

import com.seah.startupsim.game.GameTypes.Advance;
import com.seah.startupsim.game.GameResponses.Advanced;

/** 게임 행을 잠근 한 트랜잭션 안에서 결산·원장·멱등 응답을 함께 저장한다. */
@Service
public class AdvanceService {
    private record StoreCounts(int stores, long openings, long closings) {}
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
    public Advanced advance(long id,long user,String key,long expectedVersion) {
        if(key==null || key.isBlank() || key.length()>100) throw GameException.invalid("Idempotency-Key가 필요합니다.");
        var g=r.game(id,user,true);
        var cached=r.jdbc().queryForList("SELECT response::text FROM idempotency_keys WHERE game_id=? AND key=?",String.class,id,key);
        if(!cached.isEmpty()) return r.decode(cached.getFirst(), Advanced.class);
        if(g.version()!=expectedVersion) throw new GameException(409,"GAME_VERSION_CONFLICT","게임 버전이 변경되었습니다.");
        GameService.playing(g);
        int turn=g.currentTurn();
        String quarter=GameRepository.quarter(g.startQuarter(),turn);
        var decisions=r.jdbc().query("SELECT price_level,open_hours,staff,marketing,owner_activity FROM turn_decisions WHERE game_id=? AND turn=?",(row,index) -> new Decision(Price.valueOf(row.getString("price_level")),row.getInt("open_hours"),row.getInt("staff"),row.getLong("marketing"),Activity.valueOf(row.getString("owner_activity"))),id,turn);
        if(decisions.isEmpty()) throw GameException.invalid("이번 분기의 운영 결정을 저장해 주세요.");
        Shop shop = r.shop(id);
        if (shop == null) throw GameException.invalid("먼저 창업해 주세요.");
        Decision decision = decisions.getFirst();
        var sales=r.jdbc().queryForList("SELECT q.sales_amount FROM area_sales_q q JOIN game_data_pins p ON p.version_id=q.version_id AND p.game_id=? WHERE q.area_code=? AND q.service_code=? AND q.quarter=?",Long.class,id,shop.areaCode(),shop.serviceCode(),quarter);
        var counts=r.jdbc().query("SELECT q.store_count,q.opening_count,q.closing_count FROM area_stores_q q JOIN game_data_pins p ON p.version_id=q.version_id AND p.game_id=? WHERE q.area_code=? AND q.service_code=? AND q.quarter=?",(row,index) -> new StoreCounts(row.getInt(1),row.getLong(2),row.getLong(3)),id,shop.areaCode(),shop.serviceCode(),quarter);
        StoreCounts count=counts.isEmpty()?new StoreCounts(0,0,0):counts.getFirst();
        Long average=r.jdbc().queryForObject("SELECT (sum(s.sales_amount)/nullif(sum(t.store_count),0))::bigint FROM area_sales_q s JOIN area_stores_q t USING(area_code,quarter,service_code) JOIN trade_areas a ON a.area_code=s.area_code WHERE a.district_code=? AND s.quarter=? AND s.service_code=? AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=s.version_id) AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=t.version_id) AND EXISTS(SELECT 1 FROM game_data_pins p WHERE p.game_id=? AND p.version_id=a.version_id)",Long.class,shop.districtCode(),quarter,shop.serviceCode(),id,id,id);
        Long market=sales.isEmpty()?null:sales.getFirst();
        if(market==null && average==null) throw GameException.invalid("고정된 버전에 이 분기의 매출 추정 자료가 없습니다.");
        var settings = g.settings();
        var input=new QuarterCalculator.Input(g.seed(),turn,quarter,g.cash(),market,average==null?0:average,
                count.stores(),r.jdbc().queryForObject("SELECT count(*) FROM rivals WHERE game_id=?",Integer.class,id),
                shop.areaM2(),count.openings(),count.closings(),r.stats(id),r.focus(id),decision,settings,shop.districtCode());
        QuarterOutcome result=calculator.calculate(input);
        long marketSize=market==null?input.districtPerStore()*input.stores():market;
        turns.saveOutcome(id, marketSize, result);
        ledger.recordQuarter(id, result);
        for(var c:result.statChanges()) {
            if(!OwnerCandidates.STATS.contains(c.stat())) throw new IllegalStateException();
            r.jdbc().update("UPDATE owners SET "+c.stat()+"=? WHERE game_id=?",c.after(),id);
        }
        int negatives=result.cashAfter()<0?g.negativeQuarters()+1:0;
        String ending=negatives>=2?"CLOSED":quarter.equals(g.lastQuarter())?"LONG_RUNNING":null;
        r.jdbc().update("UPDATE games SET current_turn=current_turn+1,version=version+1,cash=?,negative_quarters=?,status=?,ending_type=? WHERE id=?",result.cashAfter(),negatives,ending==null?"PLAYING":"ENDED",ending,id);
        TurnResultBuilder resultBuilder = new TurnResultBuilder(result);
        eventHook.afterQuarter(new GameContext(id, user, g.seed(), turn, quarter, ending),
                result, resultBuilder);
        result = resultBuilder.build();
        turns.saveEventPresentation(id, result);
        if (ending != null) {
            events.publishEvent(new GameEndedEvent(id, ending));
        }
        var gameState = r.state(id, user);
        Advanced response = new Advanced(result, gameState.withPendingTurningPoint(resultBuilder.pendingTurningPoint()));
        String encoded=r.encode(response);
        r.jdbc().update("INSERT INTO idempotency_keys(game_id,key,response) VALUES (?,?,?::jsonb)",id,key,encoded);
        return r.decode(encoded, Advanced.class);
    }
}


