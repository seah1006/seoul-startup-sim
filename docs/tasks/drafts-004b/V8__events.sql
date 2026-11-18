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
