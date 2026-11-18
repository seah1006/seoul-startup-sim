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
