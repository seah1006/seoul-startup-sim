-- 밸런스 조정. 근거와 조정 전후 시뮬레이션 결과는 docs/balance/BALANCE_REPORT.md.
-- V8/V9가 없는 V7 위에서도 적용 가능하다. 기존 게임은 시작 시 고정한 설정을 유지한다.

-- 1. 임대료는 실측 시세가 아닌 게임 설정값. 지역별 상대 차이를 유지하며 고정비 15% 완화.
UPDATE game_settings
SET value = to_jsonb(round((value::text)::numeric * 0.85)), updated_at = now()
WHERE key LIKE 'rent.%';

-- 2. 업종별 원가율(매출 대비 식재료비). 계산기는 cost.<serviceCode>가 있으면 그 값을, 없으면 cost.FOOD를 쓴다.
--    표준 정책 12분기 생존율을 약 40~75%로 모으면서 같은 원가율에서의 업종 간 순서(실측 매출 차이)를 유지한다.
--    값마다 출처 또는 "게임 설정값" 여부는 BALANCE_REPORT.md 표 참고.
INSERT INTO game_settings (key, value) VALUES
    ('cost.CS100001', '0.55'),  -- 한식음식점
    ('cost.CS100002', '0.40'),  -- 중식음식점
    ('cost.CS100003', '0.45'),  -- 일식음식점
    ('cost.CS100004', '0.40'),  -- 양식음식점
    ('cost.CS100005', '0.40'),  -- 제과점
    ('cost.CS100006', '0.30'),  -- 패스트푸드점
    ('cost.CS100007', '0.30'),  -- 치킨전문점
    ('cost.CS100008', '0.30'),  -- 분식전문점
    ('cost.CS100009', '0.35'),  -- 호프-간이주점
    ('cost.CS100010', '0.20')   -- 커피-음료
ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now();
