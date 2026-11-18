-- V11__board.sql : 사장님 커뮤니티 게시판 (posts, comments, post_likes, post_reports, post_views)
-- 스타일 기준: V5__game.sql (bigserial PK, timestamptz, text + CHECK로 enum, users/games 참조)
-- 주의: 기존 마이그레이션 수정 금지. V8/V9/V10은 게임 작업 예약.
-- 확정본 (메인 에이전트, 2026-11-18): postgis/postgis:16-3.5에서 V1~V7 위에 적용·규칙 테스트 통과. pg_trgm 1.6 가용 확인.

-- 한국어 부분일치 검색용(LIKE/ILIKE 가속). postgis/postgis:16-3.5는 공식 postgres 이미지 기반이라 contrib에 포함.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =========================================================
-- posts
-- =========================================================
CREATE TABLE posts (
  id bigserial PRIMARY KEY,
  category text NOT NULL CHECK (category IN ('FREE','TIP','QNA','RECORD')),
  title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 100),
  content text NOT NULL CHECK (char_length(btrim(content)) BETWEEN 1 AND 10000),
  author_id bigint NOT NULL REFERENCES users(id),

  -- RECORD(엔딩 인증글) 전용: 첨부한 내 게임 + 첨부 시점 엔딩 스냅샷
  game_id bigint REFERENCES games(id),
  ending_type text,
  service_code text,
  service_name text,
  area_code text,
  area_name text,
  shop_name text,
  owner_name text,
  survived_quarters int CHECK (survived_quarters IS NULL OR survived_quarters >= 0),
  total_profit bigint,

  view_count bigint NOT NULL DEFAULT 0 CHECK (view_count >= 0),
  like_count int NOT NULL DEFAULT 0 CHECK (like_count >= 0),
  comment_count int NOT NULL DEFAULT 0 CHECK (comment_count >= 0),

  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','HIDDEN','DELETED')),
  is_pinned boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 0,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  hidden_at timestamptz,
  deleted_at timestamptz,

  -- RECORD만 게임 첨부 가능, RECORD는 게임 첨부 필수
  CONSTRAINT ck_posts_record_game CHECK (
    (category = 'RECORD' AND game_id IS NOT NULL)
    OR (category <> 'RECORD' AND game_id IS NULL)
  ),
  -- RECORD는 목록 필터/정렬에 쓰는 스냅샷이 모두 채워져야 한다
  CONSTRAINT ck_posts_record_snapshot CHECK (
    category <> 'RECORD'
    OR (ending_type IS NOT NULL AND service_code IS NOT NULL AND service_name IS NOT NULL
        AND area_code IS NOT NULL AND area_name IS NOT NULL
        AND shop_name IS NOT NULL AND owner_name IS NOT NULL
        AND survived_quarters IS NOT NULL AND total_profit IS NOT NULL)
  ),
  CONSTRAINT ck_posts_deleted_at CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

-- 같은 게임으로 RECORD 글은 1건만. soft delete(DELETED) 후 재작성 허용, 신고 숨김(HIDDEN)은 재작성 불가.
CREATE UNIQUE INDEX ux_posts_record_game
  ON posts (game_id)
  WHERE category = 'RECORD' AND status <> 'DELETED';

-- 목록 기본: 카테고리 + 최신순
CREATE INDEX ix_posts_category_created ON posts (category, created_at DESC, id DESC) WHERE status = 'ACTIVE';
-- 전체 카테고리 최신순
CREATE INDEX ix_posts_created ON posts (created_at DESC, id DESC) WHERE status = 'ACTIVE';
-- 인기/조회/댓글 정렬
CREATE INDEX ix_posts_category_likes    ON posts (category, like_count DESC, id DESC)    WHERE status = 'ACTIVE';
CREATE INDEX ix_posts_category_views    ON posts (category, view_count DESC, id DESC)    WHERE status = 'ACTIVE';
CREATE INDEX ix_posts_category_comments ON posts (category, comment_count DESC, id DESC) WHERE status = 'ACTIVE';
-- RECORD 전용 필터(업종/상권) + 수익 랭킹
CREATE INDEX ix_posts_record_service ON posts (service_code, created_at DESC, id DESC) WHERE category = 'RECORD' AND status = 'ACTIVE';
CREATE INDEX ix_posts_record_area    ON posts (area_code, created_at DESC, id DESC)    WHERE category = 'RECORD' AND status = 'ACTIVE';
CREATE INDEX ix_posts_record_profit  ON posts (total_profit DESC, id DESC)              WHERE category = 'RECORD' AND status = 'ACTIVE';
-- 내가 쓴 글, 작성자 검색
CREATE INDEX ix_posts_author ON posts (author_id, created_at DESC, id DESC);
-- 공지 고정
CREATE INDEX ix_posts_pinned ON posts (category, created_at DESC) WHERE is_pinned AND status = 'ACTIVE';
-- 관리자 신고 처리 화면(숨김 목록)
CREATE INDEX ix_posts_status ON posts (status, created_at DESC);
-- 한국어 부분일치 검색 (title / content ILIKE '%키워드%')
CREATE INDEX ix_posts_title_trgm   ON posts USING gin (title gin_trgm_ops);
CREATE INDEX ix_posts_content_trgm ON posts USING gin (content gin_trgm_ops);

-- =========================================================
-- comments (1단계 대댓글)
-- =========================================================
CREATE TABLE comments (
  id bigserial PRIMARY KEY,
  post_id bigint NOT NULL REFERENCES posts(id),
  parent_id bigint REFERENCES comments(id),
  depth smallint NOT NULL DEFAULT 0 CHECK (depth IN (0,1)),
  author_id bigint NOT NULL REFERENCES users(id),
  content text NOT NULL CHECK (char_length(btrim(content)) BETWEEN 1 AND 1000),
  reply_count int NOT NULL DEFAULT 0 CHECK (reply_count >= 0),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','HIDDEN','DELETED')),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  hidden_at timestamptz,
  deleted_at timestamptz,
  CONSTRAINT ck_comments_depth CHECK (
    (parent_id IS NULL AND depth = 0) OR (parent_id IS NOT NULL AND depth = 1)
  ),
  CONSTRAINT ck_comments_deleted_at CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

-- 루트 댓글 목록(오래된 순), 대댓글 목록
CREATE INDEX ix_comments_post_root  ON comments (post_id, created_at, id) WHERE parent_id IS NULL;
CREATE INDEX ix_comments_parent     ON comments (parent_id, created_at, id) WHERE parent_id IS NOT NULL;
CREATE INDEX ix_comments_post       ON comments (post_id, created_at, id);
CREATE INDEX ix_comments_author     ON comments (author_id, created_at DESC);

-- 대댓글은 1단계까지만. 부모가 이미 대댓글이면 거부, 부모와 post_id가 다르면 거부, depth는 서버 입력을 무시하고 강제.
CREATE FUNCTION board_comment_depth_guard() RETURNS trigger AS $$
DECLARE
  parent_post_id bigint;
  parent_parent_id bigint;
BEGIN
  IF NEW.parent_id IS NULL THEN
    NEW.depth := 0;
    RETURN NEW;
  END IF;

  SELECT post_id, parent_id INTO parent_post_id, parent_parent_id
    FROM comments WHERE id = NEW.parent_id FOR KEY SHARE;

  IF parent_post_id IS NULL THEN
    RAISE EXCEPTION 'parent comment % not found', NEW.parent_id;
  END IF;
  IF parent_parent_id IS NOT NULL THEN
    RAISE EXCEPTION 'comment depth exceeded: parent % is already a reply', NEW.parent_id
      USING ERRCODE = '23514';
  END IF;
  IF parent_post_id <> NEW.post_id THEN
    RAISE EXCEPTION 'parent comment % belongs to post %, not %', NEW.parent_id, parent_post_id, NEW.post_id
      USING ERRCODE = '23514';
  END IF;

  NEW.depth := 1;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_comments_depth_guard
  BEFORE INSERT OR UPDATE OF parent_id, post_id ON comments
  FOR EACH ROW EXECUTE FUNCTION board_comment_depth_guard();

-- =========================================================
-- post_likes (사용자당 1회)
-- =========================================================
CREATE TABLE post_likes (
  id bigserial PRIMARY KEY,
  post_id bigint NOT NULL REFERENCES posts(id),
  user_id bigint NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_post_likes_post_user UNIQUE (post_id, user_id)
);
-- 내가 좋아요한 글 목록, 좋아요 여부 조회
CREATE INDEX ix_post_likes_user ON post_likes (user_id, created_at DESC);

-- =========================================================
-- post_reports (게시글/댓글 신고, 사용자당 대상별 1회)
-- =========================================================
CREATE TABLE post_reports (
  id bigserial PRIMARY KEY,
  target_type text NOT NULL CHECK (target_type IN ('POST','COMMENT')),
  target_id bigint NOT NULL,
  reporter_id bigint NOT NULL REFERENCES users(id),
  reason text NOT NULL CHECK (reason IN ('SPAM','ABUSE','ADULT','ETC')),
  detail text CHECK (detail IS NULL OR char_length(detail) <= 500),
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RESOLVED','DISMISSED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  handled_at timestamptz,
  -- 관리자는 AdminSecurityConfig의 인메모리 계정이라 users 행이 없다 → FK 없이 username만 기록
  handled_by text,
  CONSTRAINT ux_post_reports_target_reporter UNIQUE (target_type, target_id, reporter_id),
  CONSTRAINT ck_post_reports_handled CHECK ((status = 'PENDING') = (handled_at IS NULL))
);
-- /admin 처리 대기 목록
CREATE INDEX ix_post_reports_status ON post_reports (status, created_at);
-- 대상별 신고 건수 집계
CREATE INDEX ix_post_reports_target ON post_reports (target_type, target_id);

-- =========================================================
-- post_views (조회수 중복 집계 방지: 사용자 + 날짜(KST) 기준 1회)
-- =========================================================
-- 미인증(토큰 없음) 조회는 집계하지 않는다. 유효 X-Guest-Token 조회만 KST 일자 기준 1회.
CREATE TABLE post_views (
  post_id bigint NOT NULL REFERENCES posts(id),
  user_id bigint NOT NULL REFERENCES users(id),
  viewed_on date NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, user_id, viewed_on)
);

-- =========================================================
-- RECORD 첨부 게임 이중 방어 (서비스 검증이 1차, 이 트리거는 직접 SQL·버그 대비)
-- games는 읽기만 한다. game 패키지 코드는 건드리지 않는다.
-- =========================================================
CREATE FUNCTION board_record_game_guard() RETURNS trigger AS $$
DECLARE
  g_status text;
  g_user bigint;
BEGIN
  IF NEW.category <> 'RECORD' THEN
    RETURN NEW;
  END IF;
  SELECT status, user_id INTO g_status, g_user FROM games WHERE id = NEW.game_id;
  IF g_status IS DISTINCT FROM 'ENDED' THEN
    RAISE EXCEPTION 'game % is not ENDED', NEW.game_id USING ERRCODE = '23514';
  END IF;
  IF g_user <> NEW.author_id THEN
    RAISE EXCEPTION 'game % is not owned by user %', NEW.game_id, NEW.author_id USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posts_record_game_guard
  BEFORE INSERT OR UPDATE OF game_id, category, author_id ON posts
  FOR EACH ROW EXECUTE FUNCTION board_record_game_guard();

-- 트리거 함수가 호출자의 search_path에 영향받지 않도록 고정 (교차 리뷰 반영)
ALTER FUNCTION board_comment_depth_guard() SET search_path = public;
ALTER FUNCTION board_record_game_guard() SET search_path = public;
