package com.seah.startupsim.game;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 게임 상태와 버전 고정 참조 데이터를 JDBC로 읽는다. */
@Repository
public class GameRepository {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    public GameRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public JdbcTemplate jdbc() { return jdbc; }
    String encode(Object value) { return json.writeValueAsString(value); }
    @SuppressWarnings("unchecked")
    Map<String,Object> decode(String value) { return json.readValue(value,LinkedHashMap.class); }
    Map<String,Object> game(long id,long user,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM games WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw new GameException(404,"NOT_FOUND","게임을 찾을 수 없습니다.");
        var row=rows.getFirst();
        if(((Number)row.get("user_id")).longValue()!=user) throw new GameException(403,"FORBIDDEN","다른 게스트의 게임입니다.");
        return row;
    }
    Map<String,Integer> stats(long id) {
        var row=jdbc.queryForMap("SELECT * FROM owners WHERE game_id=?",id);
        Map<String,Integer> result=new LinkedHashMap<>();
        for(String key:OwnerCandidates.STATS) result.put(key,((Number)row.get(key)).intValue());
        return result;
    }
    List<String> focus(long id) {
        var row=jdbc.queryForMap("SELECT * FROM owners WHERE game_id=?",id);
        return java.util.stream.Stream.of(row.get("focus_stat_1"),row.get("focus_stat_2")).filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }
    public Map<String,Object> state(long id,long user) {
        var g=game(id,user,false);
        var o=jdbc.queryForMap("SELECT * FROM owners WHERE game_id=?",id);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("gameId",id); result.put("version",g.get("version")); result.put("status",g.get("status"));
        result.put("endingType",g.get("ending_type")); result.put("currentTurn",g.get("current_turn"));
        result.put("startQuarter",g.get("start_quarter")); result.put("lastQuarter",g.get("last_quarter"));
        result.put("currentQuarter",quarter(g.get("start_quarter").toString(),((Number)g.get("current_turn")).intValue()));
        result.put("cash",g.get("cash"));
        result.put("owner",Map.of("name",o.get("name"),"background",o.get("background"),"stats",stats(id),"focusStats",focus(id)));
        var shops=jdbc.queryForList("SELECT area_code AS \"areaCode\",area_name AS \"areaName\",service_code AS \"serviceCode\",service_name AS \"serviceName\",name AS \"shopName\",ST_X(geom) AS lng,ST_Y(geom) AS lat,(SELECT count(*) FROM rivals WHERE game_id=?) AS \"rivalCount\" FROM shops WHERE game_id=?",id,id);
        result.put("shop",shops.isEmpty()?null:shops.getFirst());
        result.put("pendingDecision",jdbc.queryForObject("SELECT count(*)=0 FROM turn_decisions WHERE game_id=? AND turn=?",Boolean.class,id,g.get("current_turn")));
        return result;
    }
    public List<Map<String,Object>> turns(long id,long user) {
        game(id,user,false);
        return jdbc.queryForList("SELECT response::text FROM turn_results WHERE game_id=? ORDER BY turn",String.class,id).stream().map(this::decode).toList();
    }
    static String quarter(String start,int offset) {
        int n=Integer.parseInt(start.substring(0,4))*4+Integer.parseInt(start.substring(4))-1+offset;
        return ""+(n/4)+(n%4+1);
    }
    static long number(Map<String,Object> row,String key) { Object v=row.get(key); return v==null?0:((Number)v).longValue(); }
}
