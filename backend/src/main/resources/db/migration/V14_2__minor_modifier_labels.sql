-- 작은 사건 수정치 라벨을 실제 효과 수치로 다시 쓴다.
-- V14_1의 25% 축소 뒤 라벨이 "작은 사건 매출 조정"처럼 수치 없이 남아, 화면에서 무엇이 얼마나 바뀌는지 알 수 없었다.
-- 예: 매출 +1.5% · 2분기, 원가율 +0.3%p · 2분기, 임대료 -8만 원 · 2분기
CREATE FUNCTION minor_modifier_label(e jsonb) RETURNS text LANGUAGE sql IMMUTABLE AS $f$
 SELECT (CASE e->>'target' WHEN 'revenue' THEN '매출' WHEN 'visitors' THEN '방문객' WHEN 'rent' THEN '임대료' WHEN 'costRate' THEN '원가율' ELSE e->>'target' END) || ' ' ||
  CASE WHEN e->>'mode' = 'MULTIPLY' THEN
     (CASE WHEN (e->>'value')::numeric >= 1 THEN '+' ELSE '' END) || trim(trailing '.' from trim(trailing '0' from round(((e->>'value')::numeric - 1) * 100, 1)::text)) || '%'
   WHEN e->>'target' = 'costRate' THEN
     (CASE WHEN (e->>'value')::numeric >= 0 THEN '+' ELSE '' END) || trim(trailing '.' from trim(trailing '0' from round((e->>'value')::numeric * 100, 1)::text)) || '%p'
   ELSE
     (CASE WHEN (e->>'value')::numeric >= 0 THEN '+' ELSE '' END) || round((e->>'value')::numeric / 10000)::text || '만 원'
  END || ' · ' || coalesce(e->>'quarters', '1') || '분기'
$f$;

UPDATE turning_point_options o SET
    on_success = coalesce((SELECT jsonb_agg(CASE WHEN e->>'type' = 'MODIFIER' THEN jsonb_set(e, '{label}', to_jsonb(minor_modifier_label(e))) ELSE e END) FROM jsonb_array_elements(o.on_success) e), '[]'::jsonb),
    on_fail = coalesce((SELECT jsonb_agg(CASE WHEN e->>'type' = 'MODIFIER' THEN jsonb_set(e, '{label}', to_jsonb(minor_modifier_label(e))) ELSE e END) FROM jsonb_array_elements(o.on_fail) e), '[]'::jsonb)
FROM turning_points p WHERE p.id = o.turning_point_id AND p.tier = 'MINOR';

DROP FUNCTION minor_modifier_label(jsonb);
