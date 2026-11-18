package com.seah.startupsim.game;

import com.seah.startupsim.catalog.IndustryMappingPort;
import com.seah.startupsim.game.GameResponses.DecisionSaved;
import com.seah.startupsim.game.GameResponses.State;
import com.seah.startupsim.game.GameTypes.Create;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Shop;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 생성 시 데이터·설정을 고정하고, 창업·결정 변경은 소유권 확인과 행 잠금으로 보호한다. */
@Service
public class GameService {
    private record Area(String name, String districtCode, BigDecimal areaM2) {}

    private final GameRepository repository;
    private final OwnerCandidates candidates;
    private final IndustryMappingPort mapping;

    public GameService(GameRepository repository, OwnerCandidates candidates, IndustryMappingPort mapping) {
        this.repository = repository;
        this.candidates = candidates;
        this.mapping = mapping;
    }

    /** 진행 중 원본 데이터가 갱신되어도 같은 게임의 계산 근거가 바뀌지 않게 고정한다. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public State create(long user, Create request) {
        if (request.focusStats().stream().distinct().count() != request.focusStats().size()
                || !OwnerCandidates.STATS.containsAll(request.focusStats())) {
            throw GameException.invalid("집중 능력치를 확인해 주세요.");
        }
        var candidate = candidates.generate(request.background(), request.candidateSeed()).stream()
                .filter(value -> value.candidateId().equals(request.candidateId())).findFirst()
                .orElseThrow(() -> GameException.invalid("사장님 후보가 없습니다."));
        var quarters = repository.jdbc().queryForList("""
                SELECT DISTINCT q.quarter FROM area_sales_q q
                JOIN data_versions v ON v.id = q.version_id
                WHERE v.status = 'ACTIVE' ORDER BY q.quarter
                """, String.class);
        if (quarters.isEmpty()) {
            throw GameException.invalid("활성 매출 데이터가 없습니다.");
        }
        String start = request.startQuarter() == null ? quarters.getFirst() : request.startQuarter();
        if (!quarters.contains(start)) {
            throw GameException.invalid("시작 분기 데이터가 없습니다.");
        }
        Map<String, Double> settings = new LinkedHashMap<>();
        repository.jdbc().query("""
                SELECT key, value::text FROM game_settings
                """, row -> {
                    settings.put(row.getString(1), repository.settingNumber(row.getString(1), row.getString(2)));
                });
        repository.requireSettings(settings);
        long capital = settings.get("initialCapital").longValue();
        Long id = repository.jdbc().queryForObject("""
                INSERT INTO games (user_id, seed, start_quarter, last_quarter, cash, initial_capital, settings)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, user, request.candidateSeed(), start, quarters.getLast(), capital, capital,
                repository.encode(settings));
        repository.jdbc().update("""
                INSERT INTO game_data_pins (game_id, source, version_id)
                SELECT ?, source, id FROM data_versions WHERE status = 'ACTIVE'
                  AND source NOT IN ('sbiz-stores-csv', 'seoul-area-meta', 'seoul-area-shapes')
                """, id);
        var stats = candidate.stats();
        var focus = request.focusStats();
        repository.jdbc().update("""
                INSERT INTO owners (game_id, name, background, cooking, service, management, marketing,
                                    stamina, network, focus_stat_1, focus_stat_2)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.ownerName(), request.background().name(), stats.get("cooking"), stats.get("service"),
                stats.get("management"), stats.get("marketing"), stats.get("stamina"), stats.get("network"),
                focus.isEmpty() ? null : focus.getFirst(), focus.size() < 2 ? null : focus.get(1));
        return repository.state(id, user);
    }

    /** 고정된 상권과 업종의 실제 점포만 복사해 경쟁점의 출처를 유지한다. */
    @Transactional
    public State shop(long id, long user, Shop request) {
        Game game = repository.game(id, user, true);
        playing(game);
        if (repository.jdbc().queryForObject("""
                SELECT count(*) FROM shops WHERE game_id = ?
                """, Integer.class, id) > 0) {
            throw GameException.invalid("이미 창업한 게임입니다.");
        }
        var areas = repository.jdbc().query("""
                SELECT a.name, a.district_code, a.area_m2 FROM trade_areas a
                WHERE area_code = ? AND ST_Contains(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                """, (row, index) -> new Area(row.getString("name"), row.getString("district_code"),
                row.getBigDecimal("area_m2")), request.areaCode(), request.lng(), request.lat());
        if (areas.isEmpty()) {
            throw GameException.invalid("고정된 상권 영역 안의 위치를 선택해 주세요.");
        }
        Area area = areas.getFirst();
        var industries = repository.jdbc().queryForList("""
                SELECT name FROM service_industries WHERE service_code = ? AND category = 'FOOD' AND has_sales
                """, String.class, request.serviceCode());
        if (industries.isEmpty()) {
            throw GameException.invalid("창업 가능한 외식 업종을 선택해 주세요.");
        }
        if (repository.jdbc().queryForObject("""
                SELECT count(*) FROM area_sales_q q
                JOIN game_data_pins p ON p.version_id = q.version_id AND p.game_id = ? WHERE area_code = ?
                """, Integer.class, id, request.areaCode()) == 0) {
            throw GameException.invalid("매출 자료가 없는 상권입니다.");
        }
        repository.jdbc().update("""
                INSERT INTO shops (game_id, area_code, area_name, district_code, service_code, service_name,
                                   name, geom, capital, area_m2)
                VALUES (?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)
                """, id, request.areaCode(), area.name(), area.districtCode(), request.serviceCode(),
                industries.getFirst(), request.shopName(), request.lng(), request.lat(), game.initialCapital(), area.areaM2());
        for (String code : mapping.smallCodes(request.serviceCode())) {
            repository.jdbc().update("""
                    INSERT INTO rivals (game_id, sbiz_store_id, service_code, geom)
                    SELECT ?, s.store_id, ?, s.geom FROM stores s WHERE s.small_code = ?
                      AND s.geom && ST_Envelope(ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 300)::geometry)
                      AND ST_DWithin(s.geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 300)
                    """, id, request.serviceCode(), code, request.lng(), request.lat(), request.lng(), request.lat());
        }
        repository.jdbc().update("""
                UPDATE games SET version = version + 1 WHERE id = ?
                """, id);
        return repository.state(id, user);
    }

    @Transactional
    public DecisionSaved decision(long id, long user, int turn, Decision decision) {
        Game game = repository.game(id, user, true);
        playing(game);
        if (turn < game.currentTurn()) {
            throw new GameException(409, "TURN_ALREADY_ADVANCED", "이미 진행한 분기입니다.");
        }
        if (turn != game.currentTurn()) {
            throw GameException.invalid("현재 분기의 결정만 저장할 수 있습니다.");
        }
        if (decision.marketingBudget() > 1000000000000L) {
            throw GameException.invalid("마케팅 예산이 너무 큽니다.");
        }
        repository.jdbc().update("""
                INSERT INTO turn_decisions (game_id, turn, price_level, open_hours, staff, marketing, owner_activity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (game_id, turn) DO UPDATE SET price_level = excluded.price_level,
                    open_hours = excluded.open_hours, staff = excluded.staff, marketing = excluded.marketing,
                    owner_activity = excluded.owner_activity
                """, id, turn, decision.priceLevel().name(), decision.openHours(), decision.staff(),
                decision.marketingBudget(), decision.ownerActivity().name());
        return new DecisionSaved(turn, true);
    }

    static void playing(Game game) {
        if (!"PLAYING".equals(game.status())) {
            throw new GameException(409, "GAME_ENDED", "종료된 게임입니다.");
        }
    }
}
