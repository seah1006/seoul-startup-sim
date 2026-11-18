-- TASK-004B 파트 1 초안: 전환점 이벤트, 동네 반응, 이번 분기의 순간.
-- 설계 근거: docs/tasks/drafts-004b/ENGINE_DRAFT.md, docs/DESIGN.md 5·6·11장, docs/api/API_CONTRACT.md 3장.
-- 전제: V5(games, turn_results, idempotency_keys)와 V7까지 적용된 DB. V5는 수정하지 않는다.
-- 이 파일은 "스키마 + 검증 장치"만 담는다. 전환점 12개와 반응 템플릿 같은 콘텐츠 INSERT는
-- CONTENT_DRAFT.md를 확정한 뒤 이 파일 맨 아래 "-- 콘텐츠" 절에 붙인다(ENGINE_DRAFT 1.4 재현성 조건 참고).

------------------------------------------------------------------------------
-- 0. 기존 테이블 확장
------------------------------------------------------------------------------

-- 2호점 이상은 shops 행을 늘리지 않고 수치 보정으로만 표현한다(메인 파트 2 결정, 2026-11-18).
ALTER TABLE games ADD COLUMN shop_count int NOT NULL DEFAULT 1 CHECK (shop_count BETWEEN 1 AND 5);

-- 멱등 키를 advance와 전환점 선택이 함께 쓰므로, 같은 키가 다른 엔드포인트에 재사용되는 것을 구분한다.
ALTER TABLE idempotency_keys
    ADD COLUMN endpoint text NOT NULL DEFAULT 'advance'
    CHECK (endpoint IN ('advance', 'turning-point-choice', 'sell'));

-- response jsonb가 여전히 결산 카드의 원본이다. 아래 컬럼은 관리자 조회·집계용 사본.
ALTER TABLE turn_results ADD COLUMN moment jsonb;

------------------------------------------------------------------------------
-- 1. 전환점 정의 (관리자가 수정, 조건·효과는 화이트리스트 JSON)
------------------------------------------------------------------------------

CREATE TABLE turning_points (
    id                bigserial PRIMARY KEY,
    code              text NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    title             text NOT NULL CHECK (char_length(title) BETWEEN 2 AND 60),
    description       text NOT NULL DEFAULT '' CHECK (char_length(description) <= 400),
    -- 발생 조건식. {} 는 "항상 참". 노드 형식은 ENGINE_DRAFT 2.1.
    condition_expr    jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(condition_expr) = 'object'),
    -- 발생 확률: base + Σ((stat-40)/10 × per10) 을 [prob_min, prob_max]로 clamp.
    base_probability  double precision NOT NULL CHECK (base_probability BETWEEN 0 AND 1),
    stat_bonus        jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(stat_bonus) = 'array'),
    prob_min          double precision NOT NULL DEFAULT 0 CHECK (prob_min BETWEEN 0 AND 1),
    prob_max          double precision NOT NULL DEFAULT 1 CHECK (prob_max BETWEEN 0 AND 1),
    cooldown_quarters int NOT NULL DEFAULT 4 CHECK (cooldown_quarters BETWEEN 0 AND 20),
    max_fires         int NOT NULL DEFAULT 1 CHECK (max_fires BETWEEN 1 AND 10),
    min_turn          int NOT NULL DEFAULT 0 CHECK (min_turn BETWEEN 0 AND 40),
    -- 같은 분기에 여러 후보가 뽑히면 priority가 큰 것, 같으면 code 사전순이 이긴다(결정론).
    priority          int NOT NULL DEFAULT 50 CHECK (priority BETWEEN 0 AND 1000),
    enabled           boolean NOT NULL DEFAULT true,
    -- 정의가 수정될 때마다 올린다. 대기 중인 전환점 행은 payload와 함께 이 값을 스냅샷한다.
    rev               int NOT NULL DEFAULT 1 CHECK (rev >= 1),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (prob_max >= prob_min)
);
COMMENT ON COLUMN turning_points.condition_expr IS '화이트리스트 조건 DSL. 저장 전 애플리케이션이 변수·연산자를 검증한다.';
CREATE INDEX turning_points_pick_idx ON turning_points (enabled, priority DESC, code);

CREATE TABLE turning_point_options (
    id               bigserial PRIMARY KEY,
    turning_point_id bigint NOT NULL REFERENCES turning_points(id) ON DELETE CASCADE,
    order_no         int NOT NULL CHECK (order_no BETWEEN 1 AND 3),
    label            text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 40),
    -- 성공 확률 공식. 형식은 turning_points의 base/stat_bonus/min/max와 같다.
    success_formula  jsonb NOT NULL CHECK (jsonb_typeof(success_formula) = 'object'),
    on_success       jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(on_success) = 'array'),
    on_fail          jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(on_fail) = 'array'),
    success_text     text NOT NULL CHECK (char_length(success_text) BETWEEN 1 AND 300),
    fail_text        text NOT NULL DEFAULT '-' CHECK (char_length(fail_text) BETWEEN 1 AND 300),
    UNIQUE (turning_point_id, order_no)
);

-- 선택지는 2~3개여야 한다. 행 단위 CHECK로는 표현할 수 없어 트랜잭션 끝에 보는 제약 트리거로 막는다.
CREATE FUNCTION turning_point_option_count_ok() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target bigint := coalesce(NEW.turning_point_id, OLD.turning_point_id);
    n int;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM turning_points WHERE id = target) THEN
        RETURN NULL; -- 정의가 함께 삭제된 경우
    END IF;
    SELECT count(*) INTO n FROM turning_point_options WHERE turning_point_id = target;
    IF n < 2 OR n > 3 THEN
        RAISE EXCEPTION '전환점 %는 선택지가 2~3개여야 합니다 (현재 %)', target, n;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER turning_point_options_count
    AFTER INSERT OR UPDATE OR DELETE ON turning_point_options
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION turning_point_option_count_ok();

------------------------------------------------------------------------------
-- 2. 게임별 전환점 진행 (대기 → 선택 → 해결)
------------------------------------------------------------------------------

CREATE TABLE game_turning_points (
    id               bigserial PRIMARY KEY,
    game_id          bigint NOT NULL REFERENCES games(id),
    turn             int NOT NULL CHECK (turn >= 0),
    turning_point_id bigint NOT NULL REFERENCES turning_points(id),
    -- 발생 시점의 정의 리비전과 렌더된 화면 payload(제목, 설명, 선택지, 성공률%, 효과 요약)를 고정한다.
    definition_rev   int NOT NULL CHECK (definition_rev >= 1),
    payload          jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    chosen_option_id bigint REFERENCES turning_point_options(id),
    success          boolean,
    applied_effects  jsonb CHECK (applied_effects IS NULL OR jsonb_typeof(applied_effects) = 'array'),
    result_text      text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    resolved_at      timestamptz,
    -- 한 분기에 전환점은 최대 1개
    UNIQUE (game_id, turn),
    CHECK ((chosen_option_id IS NULL) = (resolved_at IS NULL)),
    CHECK (resolved_at IS NULL OR success IS NOT NULL)
);
-- 게임당 대기 중인 전환점은 동시에 하나만 존재할 수 있다.
CREATE UNIQUE INDEX game_turning_points_one_pending
    ON game_turning_points (game_id) WHERE resolved_at IS NULL;
-- 쿨다운·재발생 횟수 조회용
CREATE INDEX game_turning_points_history_idx
    ON game_turning_points (game_id, turning_point_id, turn DESC);

------------------------------------------------------------------------------
-- 3. 동네 반응 / 이번 분기의 순간 템플릿
------------------------------------------------------------------------------

CREATE TABLE reaction_templates (
    id             bigserial PRIMARY KEY,
    code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    kind           text NOT NULL CHECK (kind IN ('REVIEW', 'COMMUNITY')),
    author         text NOT NULL CHECK (char_length(author) BETWEEN 1 AND 40),
    rating         int CHECK (rating BETWEEN 1 AND 5),
    weight         int NOT NULL DEFAULT 10 CHECK (weight BETWEEN 1 AND 100),
    condition_expr jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(condition_expr) = 'object'),
    body           text NOT NULL CHECK (char_length(body) BETWEEN 2 AND 300),
    enabled        boolean NOT NULL DEFAULT true,
    rev            int NOT NULL DEFAULT 1 CHECK (rev >= 1),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- REVIEW는 별점 필수, COMMUNITY는 별점 없음
    CHECK ((kind = 'REVIEW') = (rating IS NOT NULL))
);
COMMENT ON TABLE reaction_templates IS '게임 속 가상 반응 문구. 실제 상호·매체·플랫폼 이름 금지(AGENTS.md 절대 규칙 6).';
CREATE INDEX reaction_templates_pick_idx ON reaction_templates (enabled, kind, code);

CREATE TABLE moment_templates (
    id             bigserial PRIMARY KEY,
    code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    title          text NOT NULL CHECK (char_length(title) BETWEEN 2 AND 60),
    stat           text NOT NULL CHECK (stat IN ('cooking', 'service', 'management', 'marketing', 'stamina', 'network')),
    difficulty     int NOT NULL CHECK (difficulty BETWEEN 20 AND 80),
    weight         int NOT NULL DEFAULT 10 CHECK (weight BETWEEN 1 AND 100),
    condition_expr jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(condition_expr) = 'object'),
    success_text   text NOT NULL CHECK (char_length(success_text) BETWEEN 2 AND 300),
    fail_text      text NOT NULL CHECK (char_length(fail_text) BETWEEN 2 AND 300),
    -- 순간의 '작은 효과'. 전환점보다 좁은 화이트리스트(STAT ±2, CASH 300만 이하, MODIFIER 1~2분기).
    on_success     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(on_success) = 'array'),
    on_fail        jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(on_fail) = 'array'),
    enabled        boolean NOT NULL DEFAULT true,
    rev            int NOT NULL DEFAULT 1 CHECK (rev >= 1),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX moment_templates_pick_idx ON moment_templates (enabled, code);

------------------------------------------------------------------------------
-- 4. 효과 적용 결과 (계산기 입력으로 다시 들어간다)
------------------------------------------------------------------------------

CREATE TABLE game_modifiers (
    id          bigserial PRIMARY KEY,
    game_id     bigint NOT NULL REFERENCES games(id),
    source      text NOT NULL CHECK (source IN ('TURNING_POINT', 'MOMENT', 'ADMIN')),
    source_ref  bigint,                          -- game_turning_points.id 등
    target      text NOT NULL CHECK (target IN ('revenue', 'costRate', 'rent', 'visitors')),
    mode        text NOT NULL CHECK (mode IN ('MULTIPLY', 'ADD')),
    value       double precision NOT NULL,
    label       text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 40),
    start_turn  int NOT NULL CHECK (start_turn >= 0),   -- 이 분기부터 적용
    end_turn    int NOT NULL,                           -- 이 분기는 제외(exclusive)
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (end_turn > start_turn AND end_turn - start_turn <= 8),
    CHECK (mode <> 'MULTIPLY' OR value BETWEEN 0.1 AND 3.0)
);
-- 분기 계산 시 "지금 살아 있는 보정"을 고정 순서로 읽는다.
CREATE INDEX game_modifiers_active_idx ON game_modifiers (game_id, start_turn, end_turn, target, id);

CREATE TABLE game_flags (
    game_id  bigint NOT NULL REFERENCES games(id),
    flag     text NOT NULL CHECK (flag ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    set_turn int NOT NULL CHECK (set_turn >= 0),
    PRIMARY KEY (game_id, flag)
);
COMMENT ON TABLE game_flags IS '효과로 세팅된 상태 플래그. 이후 전환점 조건 flag.<CODE>에서 참조한다. MVP 엔딩 판정에는 쓰지 않는다.';

------------------------------------------------------------------------------
-- 5. 밸런스 상수 (관리자 수정)
------------------------------------------------------------------------------

INSERT INTO game_settings(key, value) VALUES
    ('event.fire.globalCap', '1'),          -- 한 분기 최대 전환점 수
    ('event.fire.cooldownDefault', '4'),    -- 정의에 값이 없을 때 쓰는 쿨다운
    ('event.fire.minTurn', '1'),            -- 0분기(첫 결산)에는 전환점을 띄우지 않는다
    ('event.moment.perQuarter', '1'),
    ('event.moment.statSlope', '0.02'),     -- 성공 확률 = 0.5 + (stat - difficulty) × slope
    ('event.moment.minRate', '0.05'),
    ('event.moment.maxRate', '0.95'),
    ('event.reaction.min', '2'),
    ('event.reaction.max', '3'),
    ('event.rival.virtualPerQuarterCap', '2')
ON CONFLICT (key) DO NOTHING;

------------------------------------------------------------------------------
-- 6. 콘텐츠 (CONTENT_DRAFT.md 확정 후 추가)
------------------------------------------------------------------------------
-- INSERT INTO turning_points ...        (12개)
-- INSERT INTO turning_point_options ... (정의별 2~3개)
-- INSERT INTO reaction_templates ...    (25개 이상)
-- INSERT INTO moment_templates ...      (8~12개)

-- 아래 INSERT는 generate-events.ps1로 생성한다. 직접 수정하지 않는다.
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('FRANCHISE_OFFER', '프랜차이즈 가맹 제안', '서울 전역으로 매장을 늘리고 있는 신흥 브랜드 ''하루한끼 본사''에서 가맹 전환을 제안해 왔다. 본사의 물류망과 인지도를 얻는 대신 가맹비와 원가 부담을 안게 되는데, 독립 매장으로 남을지 간판을 바꿔 달지 결정해야 한다.', '{"all":[{"var":"quartersOperated","op":">=","value":8},{"var":"areaRankPct","op":"<=","value":0.3},{"var":"cash","op":">=","value":25000000},{"var":"flag.FRANCHISE_CONTRACT","op":"!=","value":true}]}'::jsonb, 0.25, '[]'::jsonb, 0, 1, 12, 1, 0, 60, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '가맹 계약 체결', '{"base":0.55,"statBonus":[{"stat":"network","per10":0.05},{"stat":"management","per10":0.03}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":-10000000,"memo":"프랜차이즈 가맹비"},{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.25,"quarters":8,"label":"가맹 브랜드 매출 +25%"},{"type":"MODIFIER","target":"costRate","mode":"ADD","value":0.04,"quarters":8,"label":"본사 물류 원가 +4%p"},{"type":"FLAG","flag":"FRANCHISE_CONTRACT","value":true},{"type":"STAT","stat":"management","delta":3},{"type":"STAT","stat":"network","delta":2}]'::jsonb, '[{"type":"CASH","amount":-6000000,"memo":"가맹 인테리어 사전 실사 및 위약금"},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '본사 물류와 브랜드 효과가 바로 붙으며 손님이 눈에 띄게 늘었습니다.', '리뉴얼 비용만 쓰고 본사와 조건이 어긋나 계약이 무산됐습니다.' FROM turning_points WHERE code = 'FRANCHISE_OFFER';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '거절하고 독립 브랜드 유지', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":1.08,"quarters":4,"label":"독립 매장 단골 호응 +8%"},{"type":"STAT","stat":"service","delta":3},{"type":"STAT","stat":"cooking","delta":2}]'::jsonb, '[]'::jsonb, '우리 가게만의 색깔을 지키기로 했습니다. 단골들이 오히려 더 반깁니다.', '-' FROM turning_points WHERE code = 'FRANCHISE_OFFER';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('SECOND_BRANCH', '2호점 확장', '장사가 안정되면서 단골들 사이에서 분점 하나 더 내도 되겠다는 이야기가 나온다. 마침 근처에 조건이 괜찮은 빈 점포가 나왔다는 소식이 들렸는데, 지금 기세를 몰아 2호점을 열지 내실을 다질지 결정해야 한다.', '{"all":[{"var":"quartersOperated","op":">=","value":6},{"var":"cumulativeProfit","op":">=","value":30000000},{"var":"cash","op":">=","value":40000000},{"var":"shopCount","op":"==","value":1}]}'::jsonb, 0.25, '[]'::jsonb, 0, 1, 8, 2, 0, 70, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '같은 상권 내 2호점 오픈', '{"base":0.6,"statBonus":[{"stat":"management","per10":0.06}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"SHOP_COUNT","delta":1},{"type":"CASH","amount":-15000000,"memo":"같은 상권 2호점 보증금·설비"},{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.35,"quarters":8,"label":"2호점 합산 매출 +35%"},{"type":"MODIFIER","target":"rent","mode":"ADD","value":5000000,"quarters":8,"label":"2호점 임대료 +500만"},{"type":"STAT","stat":"management","delta":4}]'::jsonb, '[{"type":"CASH","amount":-10000000,"memo":"2호점 준비 무산 및 계약금 손실"},{"type":"STAT","stat":"stamina","delta":-4}]'::jsonb, '같은 동네에 2호점을 열었습니다. 대기 줄이 분산되며 전체 매출이 크게 뛰었습니다.', '기존 손님만 나눠 먹는 꼴이 되어 초기 투자금을 회수하지 못했습니다.' FROM turning_points WHERE code = 'SECOND_BRANCH';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '인접 상권에 2호점 오픈', '{"base":0.45,"statBonus":[{"stat":"marketing","per10":0.04},{"stat":"network","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"SHOP_COUNT","delta":1},{"type":"CASH","amount":-18000000,"memo":"인접 상권 2호점 보증금·설비"},{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.5,"quarters":8,"label":"인접 상권 2호점 매출 +50%"},{"type":"MODIFIER","target":"rent","mode":"ADD","value":6000000,"quarters":8,"label":"2호점 임대료 +600만"},{"type":"STAT","stat":"marketing","delta":4},{"type":"STAT","stat":"network","delta":2}]'::jsonb, '[{"type":"CASH","amount":-12000000,"memo":"신규 상권 2호점 철수 손실"},{"type":"STAT","stat":"management","delta":-2},{"type":"STAT","stat":"stamina","delta":-4}]'::jsonb, '새 동네에서도 입소문이 빠르게 퍼지며 2호점이 성공적으로 자리 잡았습니다.', '새 상권의 손님 취향을 맞추지 못해 큰 손실만 남겼습니다.' FROM turning_points WHERE code = 'SECOND_BRANCH';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 3, '내실을 다지며 보류', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"STAT","stat":"management","delta":2},{"type":"STAT","stat":"stamina","delta":2}]'::jsonb, '[]'::jsonb, '무리한 확장 대신 지금 매장의 내실을 다지기로 했습니다.', '-' FROM turning_points WHERE code = 'SECOND_BRANCH';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('RENT_HIKE', '임대료 인상 통보', '최근 상권 유동인구와 매출이 오르자 건물주 박 사장이 재계약 조건으로 월세 인상을 통보해 왔다. 그대로 받아들이자니 고정비 압박이 심하고 당장 가게를 옮기자니 단골 이탈이 걱정된다.', '{"all":[{"var":"quartersOperated","op":">=","value":4},{"any":[{"var":"area.salesTrendPct","op":">=","value":0.05},{"var":"lastProfit","op":">","value":5000000}]}]}'::jsonb, 0.25, '[]'::jsonb, 0, 1, 8, 2, 0, 55, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '인상안 수용', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"MODIFIER","target":"rent","mode":"ADD","value":1500000,"quarters":8,"label":"임대료 +150만"},{"type":"STAT","stat":"stamina","delta":-2}]'::jsonb, '[]'::jsonb, '아쉽지만 자리를 지키기 위해 인상된 임대료를 받아들였습니다.', '-' FROM turning_points WHERE code = 'RENT_HIKE';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '건물주와 임대료 협상', '{"base":0.45,"statBonus":[{"stat":"network","per10":0.07},{"stat":"management","per10":0.03}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"STAT","stat":"network","delta":4},{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[{"type":"MODIFIER","target":"rent","mode":"ADD","value":2200000,"quarters":8,"label":"협상 결렬 임대료 +220만"},{"type":"CASH","amount":-3000000,"memo":"재계약 중개·조정 비용"},{"type":"STAT","stat":"network","delta":-2}]'::jsonb, '그동안의 성실한 영업과 동네 평판 덕분에 임대료를 동결했습니다.', '협상이 틀어지며 더 불리한 조건으로 재계약을 맺었습니다.' FROM turning_points WHERE code = 'RENT_HIKE';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 3, '인근 골목으로 이전', '{"base":0.65,"statBonus":[{"stat":"marketing","per10":0.05}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":-8000000,"memo":"매장 이전 및 간판 설치비"},{"type":"MODIFIER","target":"rent","mode":"ADD","value":-1200000,"quarters":8,"label":"이전 후 임대료 -120만"},{"type":"STAT","stat":"marketing","delta":3}]'::jsonb, '[{"type":"CASH","amount":-10000000,"memo":"매장 이전 및 공백 손실"},{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":0.85,"quarters":2,"label":"이전 초기 방문객 -15%"},{"type":"STAT","stat":"stamina","delta":-4}]'::jsonb, '임대료가 저렴한 골목으로 깔끔하게 이전했고, 단골들도 금세 찾아왔습니다.', '이사 후 홍보가 부족해 한동안 손님 발길이 뚝 끊겼습니다.' FROM turning_points WHERE code = 'RENT_HIKE';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('DELIVERY_APP', '배달앱 입점', '동네 주민들이 자주 쓰는 배달 플랫폼 ''달려라배달'' 영업 담당자가 수수료 할인 혜택을 앞세워 입점을 권유했다. 비 오는 날이나 늦은 밤에도 주문을 받을 수 있지만 수수료와 포장 비용, 리뷰 관리 부담이 뒤따른다.', '{"all":[{"var":"industryCategory","op":"==","value":"FOOD"},{"var":"quartersOperated","op":">=","value":2}]}'::jsonb, 0.3, '[]'::jsonb, 0, 1, 10, 1, 0, 45, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '배달 플랫폼 입점', '{"base":0.65,"statBonus":[{"stat":"marketing","per10":0.05}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":-2000000,"memo":"배달 포장재 및 초기 광고비"},{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.18,"quarters":8,"label":"배달 주문 매출 +18%"},{"type":"MODIFIER","target":"costRate","mode":"ADD","value":0.03,"quarters":8,"label":"배달 수수료·포장비 +3%p"},{"type":"FLAG","flag":"DELIVERY_ENABLED","value":true},{"type":"STAT","stat":"marketing","delta":3}]'::jsonb, '[{"type":"CASH","amount":-4000000,"memo":"배달 광고비 및 수수료 손실"},{"type":"STAT","stat":"service","delta":-2},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '배달 주문이 꾸준히 들어오며 비수기 매출을 든든하게 받쳐 줍니다.', '배달 수수료와 광고비만 빠져나가고 남는 이익이 거의 없었습니다.' FROM turning_points WHERE code = 'DELIVERY_APP';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '홀 영업과 방문 손님에 집중', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"MODIFIER","target":"costRate","mode":"ADD","value":-0.02,"quarters":4,"label":"홀 집중 원가 절감 -2%p"},{"type":"STAT","stat":"cooking","delta":2},{"type":"STAT","stat":"service","delta":2}]'::jsonb, '[]'::jsonb, '수수료 부담 없이 매장을 찾은 손님들에게 최상의 음식을 내어주기로 했습니다.', '-' FROM turning_points WHERE code = 'DELIVERY_APP';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('CREATOR_COLLAB', '맛집 크리에이터 섭외', '구독자 30만 명을 보유한 동네 탐방 영상 크리에이터 ''맛따라길따라'' 팀에서 촬영 협조 요청이 왔다. 영상이 화제가 되면 대박이지만, 갑작스러운 손님 폭주에 응대 품질이 무너지면 오히려 역효과가 날 수 있다.', '{"all":[{"var":"quartersOperated","op":">=","value":3},{"any":[{"var":"stat.cooking","op":">=","value":50},{"var":"stat.service","op":">=","value":50},{"var":"stat.marketing","op":">=","value":50}]}]}'::jsonb, 0.22, '[]'::jsonb, 0, 1, 6, 2, 0, 50, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '촬영 수락 및 적극 협조', '{"base":0.5,"statBonus":[{"stat":"cooking","per10":0.04},{"stat":"service","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":1.35,"quarters":2,"label":"크리에이터 영상 효과 +35%"},{"type":"FLAG","flag":"CREATOR_HIT","value":true},{"type":"STAT","stat":"marketing","delta":5},{"type":"STAT","stat":"network","delta":2}]'::jsonb, '[{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":0.9,"quarters":1,"label":"응대 미숙 역풍 -10%"},{"type":"STAT","stat":"service","delta":-3},{"type":"STAT","stat":"stamina","delta":-5}]'::jsonb, '영상이 화제가 되며 주말마다 가게 앞에 긴 대기 줄이 생겼습니다.', '몰려든 손님을 감당하지 못해 불친절 후기가 쏟아졌습니다.' FROM turning_points WHERE code = 'CREATOR_COLLAB';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '정중히 거절', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"STAT","stat":"service","delta":2},{"type":"STAT","stat":"stamina","delta":2}]'::jsonb, '[]'::jsonb, '조용히 내실을 다지며 단골 손님들을 더 정성껏 맞이했습니다.', '-' FROM turning_points WHERE code = 'CREATOR_COLLAB';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('REDEVELOPMENT_RUMOR', '재개발 소문', '요즘 상권 내 오래된 건물 몇 곳이 비더니 구청에 재개발 추진위원회가 들어섰다는 소문이 돌기 시작했다. 실제로 주변 점포들이 하나둘 문을 닫으며 골목 분위기가 어수선한데, 끝까지 버틸지 자리를 옮길지 정해야 한다.', '{"all":[{"var":"quartersOperated","op":">=","value":5},{"any":[{"var":"area.closingCount","op":">=","value":3},{"var":"area.closingRateDelta","op":">","value":0.5}]}]}'::jsonb, 0.25, '[]'::jsonb, 0, 1, 10, 2, 0, 65, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '끝까지 버티며 상권 지키기', '{"base":0.5,"statBonus":[{"stat":"management","per10":0.05},{"stat":"network","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.15,"quarters":3,"label":"빈 상권 생존 반사이익 +15%"},{"type":"STAT","stat":"management","delta":3},{"type":"STAT","stat":"stamina","delta":-2}]'::jsonb, '[{"type":"CASH","amount":-8000000,"memo":"상권 침체 및 유동인구 감소 손실"},{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":0.85,"quarters":2,"label":"유동인구 급감 -15%"}]'::jsonb, '소문이 잠잠해지자 빈자리를 채운 건 우리 가게를 찾은 손님들이었습니다.', '어수선한 분위기 속에 발길이 줄어 적지 않은 손실을 입었습니다.' FROM turning_points WHERE code = 'REDEVELOPMENT_RUMOR';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '권리금 받고 인근으로 이전', '{"base":0.7,"statBonus":[{"stat":"network","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":5000000,"memo":"권리금 정산 및 이전 차익"},{"type":"STAT","stat":"network","delta":2}]'::jsonb, '[{"type":"CASH","amount":-6000000,"memo":"급매 이전 손실"},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '제때 발을 빼고 새 자리에 무사히 둥지를 틀었습니다.', '급하게 점포를 넘기느라 인테리어 비용도 건지지 못했습니다.' FROM turning_points WHERE code = 'REDEVELOPMENT_RUMOR';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 3, '집기 일부 정리하며 현금 확보', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"CASH","amount":3000000,"memo":"유휴 장비·집기 매각"},{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[]'::jsonb, '장비와 집기를 일부 정리해 현금을 확보하고 다음 기회를 준비합니다.', '-' FROM turning_points WHERE code = 'REDEVELOPMENT_RUMOR';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('STAFF_TURNOVER', '직원 이탈', '가게의 핵심 역할을 하던 경력 직원 김 대리가 개인 사정과 처우 불만을 이유로 이번 달까지만 일하겠다고 통보했다. 당장 다음 분기부터 피크 타임 인력이 구멍 날 위기인데 어떻게 대처할지 정해야 한다.', '{"all":[{"var":"staff","op":">=","value":2},{"any":[{"var":"stat.management","op":"<=","value":50},{"var":"stat.service","op":"<=","value":45}]}]}'::jsonb, 0.28, '[]'::jsonb, 0, 1, 6, 3, 0, 60, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '연봉 인상 및 처우 개선으로 만류', '{"base":0.6,"statBonus":[{"stat":"service","per10":0.04},{"stat":"network","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":-3000000,"memo":"핵심 직원 인센티브 지급"},{"type":"STAT","stat":"service","delta":3},{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[{"type":"CASH","amount":-2000000,"memo":"퇴직 정산 및 공백 손실"},{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":0.9,"quarters":1,"label":"인력 공백 처리량 저하 -10%"},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '진심 어린 대우에 마음을 돌린 직원이 전보다 더 열심히 일합니다.', '처우 개선에도 불구하고 끝내 퇴사를 막지 못했습니다.' FROM turning_points WHERE code = 'STAFF_TURNOVER';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '급히 신규 직원 채용', '{"base":0.55,"statBonus":[{"stat":"management","per10":0.05}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":-2500000,"memo":"신규 채용 및 교육비"},{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[{"type":"CASH","amount":-4500000,"memo":"수습 이탈 및 재채용 비용"},{"type":"STAT","stat":"service","delta":-2},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '다행히 손발이 잘 맞는 신입을 뽑아 공백 없이 운영을 이어갔습니다.', '신입 교육에 시간과 비용만 쏟고 다시 구인 공고를 올려야 했습니다.' FROM turning_points WHERE code = 'STAFF_TURNOVER';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 3, '당분간 사장님이 직접 커버', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":0.92,"quarters":1,"label":"1인 커버 회전율 저하 -8%"},{"type":"STAT","stat":"stamina","delta":-6},{"type":"STAT","stat":"cooking","delta":1}]'::jsonb, '[]'::jsonb, '인건비 부담은 줄였지만 몸이 열 개라도 모자란 한 분기를 보냈습니다.', '-' FROM turning_points WHERE code = 'STAFF_TURNOVER';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('BURNOUT', '번아웃', '매일 새벽부터 마감까지 쉬는 날 없이 매장을 지키다 보니 어느 날 아침 몸이 천근만근 무겁다. 손님 응대도 예전처럼 웃으며 하기 힘들고 실수가 잦아졌는데, 잠시 쉬어 갈지 끝까지 강행할지 결정해야 한다.', '{"all":[{"var":"stat.stamina","op":"<=","value":35},{"var":"openHours","op":">=","value":14}]}'::jsonb, 0.35, '[]'::jsonb, 0, 1, 5, 3, 0, 80, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '단축 영업 및 충분한 휴식 선언', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":0.85,"quarters":1,"label":"휴식 단축 영업 -15%"},{"type":"STAT","stat":"stamina","delta":10},{"type":"STAT","stat":"service","delta":1}]'::jsonb, '[]'::jsonb, '잠시 속도를 늦추고 체력을 회복했습니다. 다시 달릴 힘이 생겼습니다.', '-' FROM turning_points WHERE code = 'BURNOUT';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '약 먹으며 영업 강행', '{"base":0.35,"statBonus":[{"stat":"stamina","per10":0.08}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"STAT","stat":"stamina","delta":-4},{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[{"type":"CASH","amount":-5000000,"memo":"병원 치료비 및 임시 휴업 손실"},{"type":"STAT","stat":"stamina","delta":-10},{"type":"STAT","stat":"cooking","delta":-2},{"type":"STAT","stat":"service","delta":-2}]'::jsonb, '악착같이 버텨내며 매출 구멍을 막았습니다. 다만 피로는 누적됐습니다.', '무리한 강행 끝에 몸져누워 치료비와 휴업 손실이 겹쳤습니다.' FROM turning_points WHERE code = 'BURNOUT';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('HYGIENE_INSPECTION', '위생·안전 불시 점검', '구청 위생과에서 상권 내 음식점을 대상으로 불시 위생·원산지 점검에 나섰다. 평소 주방 청결과 식자재 보관에 신경 써 왔다면 ''위생 우수 업소'' 인증을 받을 기회지만, 관리 소홀이 적발되면 과태료를 피할 수 없다.', '{"all":[{"var":"industryCategory","op":"==","value":"FOOD"},{"var":"quartersOperated","op":">=","value":3}]}'::jsonb, 0.22, '[]'::jsonb, 0, 1, 8, 2, 0, 40, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '정면으로 점검 수검 (우수 업소 도전)', '{"base":0.55,"statBonus":[{"stat":"management","per10":0.06},{"stat":"cooking","per10":0.03}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":1.12,"quarters":3,"label":"위생 우수 업소 신뢰 +12%"},{"type":"FLAG","flag":"HYGIENE_CERTIFIED","value":true},{"type":"STAT","stat":"service","delta":3},{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[{"type":"CASH","amount":-4000000,"memo":"위생 시정 명령 및 과태료"},{"type":"STAT","stat":"service","delta":-3}]'::jsonb, '모범 위생 업소 표지판을 달았습니다. 가족 단위 손님이 부쩍 늘었습니다.', '식자재 라벨링 미비로 과태료를 물고 동네에 소문이 돌았습니다.' FROM turning_points WHERE code = 'HYGIENE_INSPECTION';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '점검 전 긴급 대청소 및 설비 보수', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"CASH","amount":-2000000,"memo":"긴급 방역 및 주방 설비 보수"},{"type":"STAT","stat":"management","delta":2},{"type":"STAT","stat":"stamina","delta":-2}]'::jsonb, '[]'::jsonb, '비용을 들여 주방을 말끔히 정비한 덕분에 무탈하게 점검을 넘겼습니다.', '-' FROM turning_points WHERE code = 'HYGIENE_INSPECTION';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('LOCAL_FESTIVAL', '지역 골목 축제 부스 참여', '상인회에서 주말마다 열리는 ''별빛 골목 마켓'' 참여 매장을 모집하고 있다. 유동인구가 평소보다 크게 몰리는 대목이지만 부스 참가비와 추가 물량 준비 부담이 적지 않다.', '{"all":[{"var":"industryCategory","op":"in","value":["FOOD","RETAIL"]},{"var":"quartersOperated","op":">=","value":2}]}'::jsonb, 0.25, '[]'::jsonb, 0, 1, 6, 2, 0, 40, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '대형 부스 참여 및 이벤트 진행', '{"base":0.55,"statBonus":[{"stat":"marketing","per10":0.05},{"stat":"stamina","per10":0.03}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":6000000,"memo":"별빛 골목 마켓 부스 순수익"},{"type":"FLAG","flag":"FESTIVAL_STAR","value":true},{"type":"STAT","stat":"marketing","delta":4},{"type":"STAT","stat":"network","delta":3}]'::jsonb, '[{"type":"CASH","amount":-5000000,"memo":"우천 행사 부진 및 재고 손실"},{"type":"STAT","stat":"stamina","delta":-4}]'::jsonb, '준비한 물량이 조기 완판되며 동네 주민들에게 확실히 눈도장을 찍었습니다.', '궂은 날씨에 발길이 끊겨 준비한 재료와 참가비만 날렸습니다.' FROM turning_points WHERE code = 'LOCAL_FESTIVAL';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '매장 앞 소규모 시식·할인만 운영', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"CASH","amount":-1000000,"memo":"소규모 판촉 행사비"},{"type":"STAT","stat":"marketing","delta":2},{"type":"STAT","stat":"service","delta":1}]'::jsonb, '[]'::jsonb, '큰 부담 없이 축제 인파 일부를 매장으로 끌어들이는 데 성공했습니다.', '-' FROM turning_points WHERE code = 'LOCAL_FESTIVAL';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('CORPORATE_CATERING', '인근 오피스 단체·정기 납품 제안', '가까운 업무지구에 있는 중견기업 ''서연테크'' 총무팀에서 직원 복지용 정기 납품·제휴 계약을 제안했다. 고정 매출처가 생기면 실적 안정성이 높아지지만 납기 준수와 단가 할인 요구를 감당할 여력이 있는지 따져봐야 한다.', '{"all":[{"var":"quartersOperated","op":">=","value":4},{"any":[{"var":"stat.management","op":">=","value":45},{"var":"stat.network","op":">=","value":45}]}]}'::jsonb, 0.22, '[]'::jsonb, 0, 1, 8, 2, 0, 45, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '정기 제휴 계약 체결', '{"base":0.55,"statBonus":[{"stat":"management","per10":0.05},{"stat":"network","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.15,"quarters":4,"label":"기업 정기 납품 매출 +15%"},{"type":"STAT","stat":"management","delta":3},{"type":"STAT","stat":"network","delta":3}]'::jsonb, '[{"type":"CASH","amount":-5000000,"memo":"납기 클레임 및 계약 해지 위약금"},{"type":"STAT","stat":"management","delta":-2},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '매달 안정적인 법인 매출이 들어오며 가게 운영에 여유가 생겼습니다.', '단체 물량을 맞추다 품질이 흔들려 결국 계약을 중도 해지했습니다.' FROM turning_points WHERE code = 'CORPORATE_CATERING';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '단발성 체험 납품으로 간 보기', '{"base":0.8,"statBonus":[{"stat":"service","per10":0.02}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"CASH","amount":2500000,"memo":"단발성 기업 단체 주문 수익"},{"type":"STAT","stat":"network","delta":2}]'::jsonb, '[{"type":"CASH","amount":-1000000,"memo":"단체 주문 준비 비용 초과"}]'::jsonb, '무리하지 않는 선에서 단체 주문을 소화하며 좋은 인상을 남겼습니다.', '생각보다 손이 많이 가 인건비만 겨우 건졌습니다.' FROM turning_points WHERE code = 'CORPORATE_CATERING';
INSERT INTO turning_points (code, title, description, condition_expr, base_probability, stat_bonus, prob_min, prob_max, cooldown_quarters, max_fires, min_turn, priority, enabled) VALUES ('SIGNATURE_VIRAL', '시그니처 입소문 급증', '동네 커뮤니티 ''우리동네 이야기''에 올라온 손님 후기 한 편이 순식간에 추천을 수백 개 받으며 가게가 화제에 올랐다. 오픈 전부터 대기 줄이 생기고 문의가 빗발치는데, 물량을 공격적으로 늘릴지 품질을 지키며 한정 수량으로 갈지 결정해야 한다.', '{"all":[{"var":"quartersOperated","op":">=","value":3},{"any":[{"var":"stat.cooking","op":">=","value":55},{"var":"stat.service","op":">=","value":55}]}]}'::jsonb, 0.24, '[]'::jsonb, 0, 1, 7, 2, 0, 50, true);
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 1, '한정판 프리미엄 전략 (품질 유지)', '{"base":1.0,"min":1.0,"max":1.0}'::jsonb, '[{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.1,"quarters":2,"label":"한정판 프리미엄 +10%"},{"type":"STAT","stat":"cooking","delta":3},{"type":"STAT","stat":"service","delta":2}]'::jsonb, '[]'::jsonb, '품질을 타협하지 않는 모습에 오히려 ''진짜 맛집''이라는 평가가 굳어졌습니다.', '-' FROM turning_points WHERE code = 'SIGNATURE_VIRAL';
INSERT INTO turning_point_options (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text) SELECT id, 2, '대량 공급 확대 (매출 극대화)', '{"base":0.5,"statBonus":[{"stat":"management","per10":0.05},{"stat":"stamina","per10":0.04}],"min":0.1,"max":0.9}'::jsonb, '[{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.3,"quarters":2,"label":"입소문 물량 확대 +30%"},{"type":"CASH","amount":5000000,"memo":"입소문 특수 추가 수익"},{"type":"FLAG","flag":"VIRAL_HIT","value":true},{"type":"STAT","stat":"marketing","delta":3},{"type":"STAT","stat":"stamina","delta":-3}]'::jsonb, '[{"type":"CASH","amount":-5000000,"memo":"급조 설비 및 폐기 재고 손실"},{"type":"STAT","stat":"service","delta":-4},{"type":"STAT","stat":"cooking","delta":-2}]'::jsonb, '발 빠르게 물량을 늘려 입소문 특수를 온전히 수익으로 연결했습니다.', '급하게 물량을 늘리다 품질이 떨어졌다는 혹평이 쏟아졌습니다.' FROM turning_points WHERE code = 'SIGNATURE_VIRAL';
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_GENERIC_1', 'REVIEW', '김서윤', 4, 8, '{}'::jsonb, '{areaName} 지나갈 때마다 들르는 {shopName}이에요. 늘 한결같아서 좋습니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_GENERIC_2', 'REVIEW', '박지성', 4, 8, '{}'::jsonb, '동네에서 무난하게 믿고 가는 {serviceName} 매장입니다. 재방문 의사 있어요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_GENERIC_3', 'REVIEW', '강하늘', 4, 7, '{}'::jsonb, '{shopName} 매장이 깔끔해서 편하게 이용하고 갑니다. 다음에도 들를게요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PROFIT_UP_1', 'REVIEW', '이수진', 5, 10, '{"var":"bucket.profit","op":"==","value":"UP"}'::jsonb, '요즘 {areaName}에서 제일 기분 좋게 나오는 {shopName}이에요. 사장님 표정도 밝고 정갈해서 자주 오게 됩니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PROFIT_UP_RANK_TOP', 'REVIEW', '박민준', 5, 12, '{"all":[{"var":"bucket.profit","op":"==","value":"UP"},{"var":"areaRankPct","op":"<=","value":0.3}]}'::jsonb, '{shopName}은 웨이팅이 조금 있긴 한데 회전이 빨라서 금방 들어가요. {areaName} {serviceName} 1등 할 만합니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PROFIT_UP_2', 'REVIEW', '이지훈', 4, 10, '{"var":"bucket.profit","op":"==","value":"UP"}'::jsonb, '가격 대비 만족도가 높아서 점심마다 {shopName}에 들릅니다. 직원분들도 손발이 잘 맞네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_LOSS_1', 'REVIEW', '최수빈', 2, 10, '{"var":"bucket.profit","op":"==","value":"LOSS"}'::jsonb, '{shopName}이 예전만 못한 느낌이에요. 손님이 적어서인지 매장 분위기도 좀 어수선했습니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_LOSS_STREAK', 'REVIEW', '정우성', 1, 12, '{"all":[{"var":"bucket.profit","op":"==","value":"LOSS"},{"var":"negativeQuarters","op":">=","value":1}]}'::jsonb, '응대도 오래 걸리고 전체적인 컨디션이 아쉬웠습니다. {areaName}에 다른 곳도 많아서 재방문은 고민되네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PROFIT_FLAT', 'REVIEW', '한예슬', 3, 10, '{"var":"bucket.profit","op":"in","value":["FLAT","DOWN"]}'::jsonb, '무난하게 들르기 괜찮은 {serviceName} 매장입니다. 특별하진 않아도 거슬리는 점은 없어요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_RANK_UP_1', 'REVIEW', '오현우', 5, 11, '{"var":"bucket.rank","op":"==","value":"UP"}'::jsonb, '지난 분기보다 확실히 {shopName}이 더 좋아졌어요! 사장님이 연구 많이 하신 티가 납니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_RANK_UP_2', 'REVIEW', '유진아', 4, 9, '{"var":"bucket.rank","op":"==","value":"UP"}'::jsonb, '{areaName}에서 요즘 입소문 타고 순위가 쑥 오른 {shopName}, 직접 와보니 이유를 알겠네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_RANK_DOWN_1', 'REVIEW', '강다은', 2, 10, '{"var":"bucket.rank","op":"==","value":"DOWN"}'::jsonb, '근처에 새 가게들이 생기더니 {shopName}은 살짝 힘이 빠진 느낌이에요. 초심 찾으셨으면.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PRICE_HIGH_GOOD', 'REVIEW', '윤도현', 4, 10, '{"all":[{"var":"bucket.price","op":"==","value":"HIGH"},{"var":"stat.cooking","op":">=","value":50}]}'::jsonb, '가격대는 좀 있는 편이지만 퀄리티를 보면 충분히 납득됩니다. 대접할 때 {shopName} 좋네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PRICE_HIGH_BAD', 'REVIEW', '임소라', 2, 10, '{"all":[{"var":"bucket.price","op":"==","value":"HIGH"},{"var":"stat.cooking","op":"<","value":50}]}'::jsonb, '{areaName} 물가 감안해도 너무 비싼 것 같아요. 이 가격이면 다른 {serviceName} 갈 듯합니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PRICE_LOW_1', 'REVIEW', '서지민', 5, 11, '{"var":"bucket.price","op":"==","value":"LOW"}'::jsonb, '요즘 물가에 이 가격으로 이용할 수 있는 게 감사할 따름입니다. {shopName} 가성비 최고!', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_PRICE_LOW_CROWDED', 'REVIEW', '조현석', 3, 9, '{"all":[{"var":"bucket.price","op":"==","value":"LOW"},{"var":"staff","op":"<=","value":1}]}'::jsonb, '싸긴 한데 사람이 몰릴 때는 조금 정신이 없어요. 그래도 가격 생각하면 이해됩니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_HOURS_LONG', 'REVIEW', '배수지', 5, 10, '{"var":"bucket.hours","op":"==","value":"LONG"}'::jsonb, '야근하고 늦게 끝나도 {shopName}은 문이 열려 있어서 너무 든든해요. 밤늦게 들르기 딱입니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_HOURS_SHORT', 'REVIEW', '송민호', 3, 10, '{"var":"bucket.hours","op":"==","value":"SHORT"}'::jsonb, '좋긴 한데 문 닫는 시간이 너무 빨라서 퇴근 후에는 {shopName} 가기가 힘드네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_FIRST_QUARTER_1', 'REVIEW', '신세경', 4, 12, '{"var":"bucket.firstQuarter","op":"==","value":true}'::jsonb, '{areaName} 골목에 {shopName}이 새로 생겼길래 가봤는데 깔끔하고 사장님이 친절하셔서 좋았습니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_FIRST_QUARTER_PROFIT', 'REVIEW', '남주혁', 5, 12, '{"all":[{"var":"bucket.firstQuarter","op":"==","value":true},{"var":"bucket.profit","op":"==","value":"UP"}]}'::jsonb, '오픈 첫 분기부터 이 정도 퀄리티면 금방 {areaName} 터줏대감 되겠는데요? {shopName} 응원합니다!', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_TP_CREATOR_HIT', 'REVIEW', '김태리', 5, 15, '{"all":[{"var":"lastResolved.code","op":"==","value":"CREATOR_COLLAB"},{"var":"lastResolved.result","op":"==","value":"SUCCESS"},{"var":"lastResolved.quartersSince","op":"<=","value":1}]}'::jsonb, '영상 보고 {shopName} 찾아왔는데 화면에서 보던 것보다 훨씬 만족스러워서 놀랐어요!', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_TP_CREATOR_FAIL', 'REVIEW', '류준열', 1, 15, '{"all":[{"var":"lastResolved.code","op":"==","value":"CREATOR_COLLAB"},{"var":"lastResolved.result","op":"==","value":"FAIL"},{"var":"lastResolved.quartersSince","op":"<=","value":1}]}'::jsonb, '소문 듣고 멀리서 {shopName} 왔는데 직원분들도 정신없고 응대가 너무 아쉬워서 실망했습니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_TP_HYGIENE', 'REVIEW', '박보영', 5, 14, '{"all":[{"var":"lastResolved.code","op":"==","value":"HYGIENE_INSPECTION"},{"var":"lastResolved.result","op":"==","value":"SUCCESS"},{"var":"lastResolved.quartersSince","op":"<=","value":1}]}'::jsonb, '{shopName} 매장이 정말 반짝반짝 깨끗해요. 위생 인증까지 받았다니 믿고 자주 옵니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('REV_VISITORS_UP', 'REVIEW', '이동욱', 4, 9, '{"var":"bucket.visitors","op":"==","value":"UP"}'::jsonb, '요즘 {shopName}에 손님이 부쩍 많아졌네요. 활기가 넘쳐서 덩달아 기분이 좋습니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_PROFIT_TOP', 'COMMUNITY', '우리동네 이야기', NULL, 10, '{"all":[{"var":"bucket.profit","op":"==","value":"UP"},{"var":"areaRankPct","op":"<=","value":0.3}]}'::jsonb, '{areaName} 사거리 쪽 {shopName} 요즘 줄 서는 거 보셨나요? 우리 동네 상권 살아나는 느낌이라 좋네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_LOSS_WORRY', 'COMMUNITY', '서울골목 커뮤니티', NULL, 10, '{"any":[{"var":"bucket.profit","op":"==","value":"LOSS"},{"var":"negativeQuarters","op":">=","value":1}]}'::jsonb, '저기 {areaName} 코너 {shopName} 요즘 손님이 많이 준 것 같던데... 자영업자분들 다들 힘내셨으면 합니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_RANK_UP', 'COMMUNITY', '점심메뉴 연구소', NULL, 10, '{"var":"bucket.rank","op":"==","value":"UP"}'::jsonb, '지난주에 {shopName} 다녀왔는데 진짜 괜찮아요. 이제 {areaName} {serviceName} 리스트 1순위입니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_RANK_DOWN', 'COMMUNITY', '동네산책 톡방', NULL, 9, '{"var":"bucket.rank","op":"==","value":"DOWN"}'::jsonb, '요즘 {areaName} 옆 골목에 신상 가게가 많이 생겨서 그런지 {shopName}이 조금 밀리는 분위기네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_PRICE_HIGH', 'COMMUNITY', '알뜰살림 모임', NULL, 9, '{"var":"bucket.price","op":"==","value":"HIGH"}'::jsonb, '{areaName}에 있는 {shopName} 가격표 보고 살짝 놀랐어요. 분위기는 좋은데 자주 가긴 부담스럽네요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_PRICE_LOW', 'COMMUNITY', '서울자취생 수다방', NULL, 10, '{"var":"bucket.price","op":"==","value":"LOW"}'::jsonb, '지갑 가벼울 때 믿고 가는 {areaName} 가성비 성지 {shopName}! 사장님 오래오래 장사해 주세요.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_HOURS_LONG', 'COMMUNITY', '야근러 쉼터', NULL, 9, '{"var":"bucket.hours","op":"==","value":"LONG"}'::jsonb, '밤 늦게까지 불 켜져 있는 {shopName}이 {areaName}에 있어서 얼마나 다행인지 모릅니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_HOURS_SHORT', 'COMMUNITY', '주말나들이 톡', NULL, 9, '{"var":"bucket.hours","op":"==","value":"SHORT"}'::jsonb, '평일에 {shopName} 가보려다 문 닫혀서 두 번 허탕 쳤어요. 가실 분들 영업시간 꼭 확인하세요!', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_FIRST_QUARTER', 'COMMUNITY', '신상탐험대', NULL, 11, '{"var":"bucket.firstQuarter","op":"==","value":true}'::jsonb, '이번 분기에 {areaName}에 새 간판 달린 {shopName} 가보신 분 계신가요? 분위기 괜찮아 보이던데.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_TP_SECOND_BRANCH', 'COMMUNITY', '우리동네 이야기', NULL, 15, '{"var":"shopCount","op":">=","value":2}'::jsonb, '{shopName} 본점 장사 잘되더니 2호점까지 냈더군요. 요즘 {areaName}에서 제일 잘나가는 듯!', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_TP_FESTIVAL', 'COMMUNITY', '별빛마을 주민회', NULL, 14, '{"all":[{"var":"lastResolved.code","op":"==","value":"LOCAL_FESTIVAL"},{"var":"lastResolved.result","op":"==","value":"SUCCESS"},{"var":"lastResolved.quartersSince","op":"<=","value":1}]}'::jsonb, '이번 골목 축제 때 부스 줄이 끝도 없던 {shopName}, 알고 보니 우리 동네 가게였네요. 뿌듯합니다.', true);
INSERT INTO reaction_templates (code, kind, author, rating, weight, condition_expr, body, enabled) VALUES ('COM_AREA_CLOSINGS', 'COMMUNITY', '서울골목 커뮤니티', NULL, 10, '{"var":"area.closingCount","op":">=","value":3}'::jsonb, '이번 분기에만 {areaName} 골목에서 몇 곳이 간판을 내렸네요. 살아남는 {shopName}은 다 이유가 있나 봅니다.', true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_REGULARS', '단골손님의 친구 동반 방문', 'service', 45, 10, '{}'::jsonb, '단골손님이 직장 동료들을 한 무더기 데려와 매장이 활기를 띠었습니다.', '단골손님이 왔지만 응대가 바빠 인사도 제대로 못 했습니다.', '[{"type":"STAT","stat":"service","delta":1},{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":1.05,"quarters":1,"label":"지인 방문 유입 +5%"}]'::jsonb, '[]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_TASTING', '계절 한정 메뉴 시식회', 'cooking', 50, 10, '{"var":"industryCategory","op":"==","value":"FOOD"}'::jsonb, '즉석에서 내놓은 계절 한정 메뉴가 손님들에게 호평받았습니다.', '새 메뉴의 간이 조금 들쑥날쑥해 아쉬운 소리를 들었습니다.', '[{"type":"STAT","stat":"cooking","delta":2}]'::jsonb, '[{"type":"STAT","stat":"cooking","delta":1}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_GROUP_BUY', '옆 가게와 식자재·비품 공동 구매', 'network', 45, 10, '{}'::jsonb, '옆 가게 사장님과 비품을 공동 구매해 원가를 쏠쏠하게 아꼈습니다.', '도매처 일정이 엇갈려 공동 구매가 다음 분기로 미뤄졌습니다.', '[{"type":"MODIFIER","target":"costRate","mode":"ADD","value":-0.02,"quarters":1,"label":"공동 구매 원가 -2%p"},{"type":"STAT","stat":"network","delta":1}]'::jsonb, '[]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_WORKFLOW', '피크 타임 동선 개선', 'management', 50, 10, '{}'::jsonb, '매장 동선을 다시 짜서 점심 회전율이 눈에 띄게 빨라졌습니다.', '동선을 바꿨다가 오히려 동선이 꼬여 잠시 혼선이 빚어졌습니다.', '[{"type":"STAT","stat":"management","delta":2}]'::jsonb, '[{"type":"STAT","stat":"stamina","delta":-1}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_LOCAL_PROMO', '동네 소식지 & 입소문 홍보', 'marketing', 45, 10, '{}'::jsonb, '직접 만든 매장 소식이 동네 주민들 사이에서 활발히 공유됐습니다.', '홍보 문구가 눈에 잘 띄지 않아 별다른 반응 없이 지나갔습니다.', '[{"type":"STAT","stat":"marketing","delta":2},{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":1.04,"quarters":1,"label":"입소문 유입 +4%"}]'::jsonb, '[{"type":"STAT","stat":"marketing","delta":1}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_HOLIDAY_RUSH', '연휴 주말 마라톤 영업', 'stamina', 50, 10, '{"var":"openHours","op":">=","value":10}'::jsonb, '연휴 기간 손님이 몰렸지만 지치지 않고 끝까지 미소를 지켰습니다.', '연휴 막바지에 체력이 급격히 떨어져 마감을 일찍 해야 했습니다.', '[{"type":"STAT","stat":"stamina","delta":2},{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.05,"quarters":1,"label":"연휴 호평 재방문 +5%"}]'::jsonb, '[{"type":"STAT","stat":"stamina","delta":-2}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_TOUGH_CUSTOMER', '까다로운 손님 응대', 'service', 55, 10, '{}'::jsonb, '불만을 제기한 손님을 차분하게 달래 오히려 단골로 만들었습니다.', '언성이 높아진 손님 때문에 매장 분위기가 잠시 얼어붙었습니다.', '[{"type":"STAT","stat":"service","delta":2},{"type":"STAT","stat":"network","delta":1}]'::jsonb, '[{"type":"STAT","stat":"service","delta":-1}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_ZERO_WASTE', '재고 로스 제로 달성', 'management', 55, 10, '{}'::jsonb, '발주량을 칼같이 맞춰 이번 분기 폐기 재고를 거의 없앴습니다.', '예상보다 손님이 적어 남은 재고를 일부 폐기해야 했습니다.', '[{"type":"MODIFIER","target":"costRate","mode":"ADD","value":-0.02,"quarters":1,"label":"재고 관리 원가 -2%p"},{"type":"STAT","stat":"management","delta":1}]'::jsonb, '[{"type":"MODIFIER","target":"costRate","mode":"ADD","value":0.02,"quarters":1,"label":"폐기 로스 원가 +2%p"}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_CRITIC_VISIT', '까다로운 미식가의 방문', 'cooking', 60, 8, '{"var":"quartersOperated","op":">=","value":2}'::jsonb, '한 미식가 손님이 기본기가 탄탄하다며 칭찬을 남겼습니다.', '무난하지만 한 방이 아쉽다는 솔직한 피드백을 받았습니다.', '[{"type":"STAT","stat":"cooking","delta":2},{"type":"STAT","stat":"marketing","delta":1}]'::jsonb, '[{"type":"STAT","stat":"cooking","delta":1}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_MERCHANT_MEETING', '상인회 친목 모임', 'network', 50, 10, '{}'::jsonb, '상인회 모임에서 동네 상권의 알짜 정보를 미리 귀띔받았습니다.', '모임에 얼굴만 비추고 바빠서 금방 자리에서 일어나야 했습니다.', '[{"type":"STAT","stat":"network","delta":2},{"type":"STAT","stat":"management","delta":1}]'::jsonb, '[{"type":"STAT","stat":"network","delta":1}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_RAINY_DAY', '비 오는 날의 감성 이벤트', 'marketing', 50, 10, '{}'::jsonb, '비 오는 날 특별 이벤트를 열어 썰렁할 뻔한 매장을 손님으로 채웠습니다.', '비가 너무 많이 와서 이벤트를 알리기도 전에 거리가 텅 비었습니다.', '[{"type":"MODIFIER","target":"visitors","mode":"MULTIPLY","value":1.05,"quarters":1,"label":"우천 이벤트 재방문 +5%"},{"type":"STAT","stat":"marketing","delta":1}]'::jsonb, '[{"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":0.97,"quarters":1,"label":"우천 홍보 손실 -3%"}]'::jsonb, true);
INSERT INTO moment_templates (code, title, stat, difficulty, weight, condition_expr, success_text, fail_text, on_success, on_fail, enabled) VALUES ('MOMENT_DAWN_MARKET', '새벽 시장 직거래 발품', 'stamina', 55, 9, '{}'::jsonb, '새벽 일찍 시장에 나가 싱싱한 물품을 저렴하게 공수해 왔습니다.', '새벽 강행군 여파로 오전 영업 내내 졸음과 싸워야 했습니다.', '[{"type":"MODIFIER","target":"costRate","mode":"ADD","value":-0.02,"quarters":1,"label":"직거래 매입 원가 -2%p"},{"type":"STAT","stat":"cooking","delta":1}]'::jsonb, '[{"type":"STAT","stat":"stamina","delta":-2}]'::jsonb, true);
