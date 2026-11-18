-- V12: 전환점 빈도 조정과 007B 후속 정리.
-- 근거와 조정 전후 시뮬레이션 결과는 docs/balance/EVENT_BALANCE_REPORT.md.
-- 1절의 UPDATE 형식(숫자 열 = 값, condition_expr = '...'::jsonb, WHERE code = '...')은
-- EventBalanceSimulationTest가 그대로 읽어 메모리 정의에 적용하므로 형식을 바꾸지 않는다.

------------------------------------------------------------------------------
-- 1. 전환점 빈도: 12분기 완주 게임당 평균 3~5회, 같은 전환점 반복은 드물게
------------------------------------------------------------------------------
UPDATE turning_points SET base_probability = 0.15, rev = rev + 1, updated_at = now() WHERE code = 'SECOND_BRANCH';
UPDATE turning_points SET base_probability = 0.12, rev = rev + 1, updated_at = now() WHERE code = 'REDEVELOPMENT_RUMOR';
UPDATE turning_points SET base_probability = 0.20, condition_expr = '{"all": [{"op": ">=", "var": "quartersOperated", "value": 8}, {"op": "<=", "var": "areaRankPct", "value": 0.8}, {"op": ">=", "var": "cash", "value": 25000000}, {"op": "!=", "var": "flag.FRANCHISE_CONTRACT", "value": true}]}'::jsonb, rev = rev + 1, updated_at = now() WHERE code = 'FRANCHISE_OFFER';
UPDATE turning_points SET base_probability = 0.08, cooldown_quarters = 8, max_fires = 2, rev = rev + 1, updated_at = now() WHERE code = 'STAFF_TURNOVER';
UPDATE turning_points SET base_probability = 0.10, rev = rev + 1, updated_at = now() WHERE code = 'RENT_HIKE';
UPDATE turning_points SET base_probability = 0.08, cooldown_quarters = 8, rev = rev + 1, updated_at = now() WHERE code = 'CREATOR_COLLAB';
UPDATE turning_points SET base_probability = 0.10, cooldown_quarters = 8, rev = rev + 1, updated_at = now() WHERE code = 'SIGNATURE_VIRAL';
UPDATE turning_points SET base_probability = 0.08, rev = rev + 1, updated_at = now() WHERE code = 'CORPORATE_CATERING';
UPDATE turning_points SET base_probability = 0.12, rev = rev + 1, updated_at = now() WHERE code = 'DELIVERY_APP';
UPDATE turning_points SET base_probability = 0.08, rev = rev + 1, updated_at = now() WHERE code = 'HYGIENE_INSPECTION';
UPDATE turning_points SET base_probability = 0.08, cooldown_quarters = 8, rev = rev + 1, updated_at = now() WHERE code = 'LOCAL_FESTIVAL';

------------------------------------------------------------------------------
-- 2. V9 이전에 끝난 게임의 엔딩 기록 백필
------------------------------------------------------------------------------
-- V9 전에는 매각 엔딩이 없었으므로 CLOSED·LONG_RUNNING만 대상이다. 숫자는 GameRecordRepository.insert와 같은
-- 식(turn_results 집계)으로, 이름·업종·상권은 현재 shops·owners·users 값으로 채운다. 종료 시각 기록이 없어
-- 마지막 진행 요청(멱등 키) 시각을, 그것도 없으면 마이그레이션 시각을 쓴다. 2026-11-18 메인 DB에는 대상 0건.
INSERT INTO game_records
    (game_id, user_id, nickname, ending_type, survived_quarters, total_profit, best_quarter_profit,
     shop_count, sale_value, area_code, area_name, service_code, service_name, shop_name, owner_name,
     ended_at, ranking_date)
SELECT g.id, g.user_id, u.nickname, g.ending_type,
       (SELECT count(*) FROM turn_results t WHERE t.game_id = g.id),
       (SELECT coalesce(sum(t.profit), 0) FROM turn_results t WHERE t.game_id = g.id),
       (SELECT coalesce(max(t.profit), 0) FROM turn_results t WHERE t.game_id = g.id),
       g.shop_count, NULL, s.area_code, s.area_name, s.service_code, s.service_name, s.name, o.name,
       e.ended_at, (e.ended_at AT TIME ZONE 'Asia/Seoul')::date
FROM games g
JOIN users u ON u.id = g.user_id
JOIN shops s ON s.game_id = g.id
JOIN owners o ON o.game_id = g.id
CROSS JOIN LATERAL (SELECT coalesce((SELECT max(k.created_at) FROM idempotency_keys k WHERE k.game_id = g.id), now())
                    AS ended_at) e
WHERE g.status = 'ENDED' AND g.ending_type IN ('CLOSED', 'LONG_RUNNING')
  AND NOT EXISTS (SELECT 1 FROM game_records r WHERE r.game_id = g.id);

------------------------------------------------------------------------------
-- 3. 효과 라벨의 금액 단위: "임대료 +150만" → "임대료 +150만 원" (현금 요약 "-300만 원"과 같은 표기)
------------------------------------------------------------------------------
UPDATE turning_points SET rev = rev + 1, updated_at = now()
WHERE id IN (SELECT turning_point_id FROM turning_point_options
             WHERE on_success::text ~ '"label": "[^"]*[0-9]만"' OR on_fail::text ~ '"label": "[^"]*[0-9]만"');
UPDATE turning_point_options
SET on_success = regexp_replace(on_success::text, '("label": "[^"]*[0-9]만)"', '\1 원"', 'g')::jsonb,
    on_fail = regexp_replace(on_fail::text, '("label": "[^"]*[0-9]만)"', '\1 원"', 'g')::jsonb
WHERE on_success::text ~ '"label": "[^"]*[0-9]만"' OR on_fail::text ~ '"label": "[^"]*[0-9]만"';
