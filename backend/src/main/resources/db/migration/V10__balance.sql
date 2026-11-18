-- 임대료는 실측 시세가 아닌 게임 설정값. 지역별 상대 차이를 유지하며 고정비 15% 완화.
-- V8/V9가 없는 V7 위에서도 적용 가능하다. 기존 게임은 시작 시 고정한 설정을 유지한다.
UPDATE game_settings
SET value = to_jsonb(round((value::text)::numeric * 0.85)), updated_at = now()
WHERE key LIKE 'rent.%';
