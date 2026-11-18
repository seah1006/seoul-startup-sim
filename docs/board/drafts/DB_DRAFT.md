# 게시판 DB 설계 초안 (DB 담당, 2026-11-18)

대상: `docs/board/drafts/` 초안. 최종 통합(DESIGN.md 19장)은 메인 에이전트가 한다.
기준 문서: `docs/DESIGN.md` 11장 ERD, `backend/src/main/resources/db/migration/V5__game.sql`, `AGENTS.md`.
마이그레이션 파일: `V11__board.sql` (기존 마이그레이션 수정 없음, 새 파일만 추가).

API 담당(Codex)·기획 담당(Gemini)과 메시지로 합의한 이름을 그대로 썼다. 합의 내용은 11장에 정리.

---

## 1. ERD (DESIGN.md 11장 형식)

### 게시판
```
posts         (id, category[FREE|TIP|QNA|RECORD], title, content, author_id → users,
               game_id NULL → games, ending_type NULL, service_code NULL, service_name NULL,
               area_code NULL, area_name NULL, shop_name NULL, owner_name NULL,
               survived_quarters NULL, total_profit NULL,
               view_count, like_count, comment_count,
               status[ACTIVE|HIDDEN|DELETED], is_pinned, version,
               created_at, updated_at, hidden_at NULL, deleted_at NULL)
                                     UNIQUE(game_id) WHERE category='RECORD' AND status<>'DELETED'

comments      (id, post_id → posts, parent_id NULL → comments, depth[0|1], author_id → users,
               content, reply_count, status[ACTIVE|HIDDEN|DELETED], version,
               created_at, updated_at, hidden_at NULL, deleted_at NULL)
                                     CHECK (parent_id IS NULL) = (depth=0), 트리거로 깊이 1 강제

post_likes    (id, post_id → posts, user_id → users, created_at)        UNIQUE(post_id, user_id)

post_reports  (id, target_type[POST|COMMENT], target_id, reporter_id → users,
               reason[SPAM|ABUSE|ADULT|ETC], detail NULL,
               status[PENDING|RESOLVED|DISMISSED], created_at, handled_at NULL, handled_by NULL text)
                                     UNIQUE(target_type, target_id, reporter_id)

post_views    (post_id → posts, user_id → users, viewed_on date, created_at)
                                     PRIMARY KEY(post_id, user_id, viewed_on)   조회수 중복 방지
```

### 기존 테이블과의 연결 (읽기 전용, game 패키지 수정 금지)
```
users(id)  ←  posts.author_id, comments.author_id, post_likes.user_id, post_reports.reporter_id, post_views.user_id
               (post_reports.handled_by는 FK 아님: 관리자는 AdminSecurityConfig 인메모리 계정이라 users 행이 없다 - 기획 담당 지적 반영)
games(id)  ←  posts.game_id   (RECORD 글만. status='ENDED' + 소유자 검증은 서비스 + 트리거)
game_records(game_id) : 엔딩 스냅샷의 원천 (ending_type, survived_quarters, total_profit, area_code, service_code)
notices : 패치노트/운영 공지 전용으로 유지. 게시판 공지는 posts.is_pinned로 처리 (10장 참고)
```

### 테이블별 근거
| 테이블 | 왜 필요한가 | 설계 근거 |
|---|---|---|
| `posts` | 게시판 CRUD의 본체. 카테고리 4종을 한 테이블로 둬 목록·검색·정렬 쿼리를 하나로 유지 | 카테고리별 테이블 분리는 "전체 글 최신순", 통합 검색이 UNION이 되어 손해. 카테고리는 컬럼 + 부분 인덱스로 처리 |
| `posts`의 엔딩 스냅샷 컬럼 | RECORD 목록에서 업종·상권 필터와 누적 순이익 정렬을 **조인 없이** 하기 위함. 목록 카드에 "별빛커피 (김사장)"을 표시하기 위함. 게임 데이터는 데이터 버전이 바뀌거나 game 패키지가 리팩터링돼도 글은 그대로 남아야 한다 | DESIGN.md 11장의 "게임 시작 시 rivals 복사" 와 같은 스냅샷 원칙. 조인 기반으로 하면 `games`+`shops`+`game_records` 3중 조인이 목록 쿼리마다 필요 |
| `comments` | 댓글 + 1단계 대댓글. `parent_id` 자기참조 + `depth` 로 구조 고정 | closure table/ltree는 1단계에 과한 구조. 깊이 2 이상 유입을 트리거로 DB에서 거부 |
| `post_likes` | 사용자당 1회 보장이 **DB 제약**이어야 동시 요청에서 안전 | 애플리케이션 체크(조회 후 삽입)는 동시 요청에서 중복 생성됨. UNIQUE + ON CONFLICT로 해결 |
| `post_reports` | 신고 → 관리자 숨김 흐름. 중복 신고 방지 | 게시글/댓글 공통 처리를 위해 polymorphic(target_type+target_id). 대안은 9장 |
| `post_views` | 조회수 어뷰징/새로고침 중복 방지 | 조회 1건마다 `UPDATE posts SET view_count=...` 하면 같은 행에 락이 몰리고 숫자도 뻥튀김 |

왜 `posts`에 카운터 컬럼(`like_count`, `comment_count`, `view_count`)을 두는가: 목록 20건마다 `COUNT(*)` 서브쿼리를 돌리면 "인기순 정렬"이 전체 스캔이 된다. 정렬 키로 쓰려면 비정규화 컬럼이 사실상 필수다. 대신 7장의 동시성 규칙을 지켜야 한다.

---

## 2. 마이그레이션 `V11__board.sql`

실제 파일: `docs/board/drafts/V11__board.sql` (구현 시 `backend/src/main/resources/db/migration/V11__board.sql` 로 이동).
V5 스타일을 따랐다: `bigserial` PK, `timestamptz` + `DEFAULT now()`, enum은 `text` + `CHECK`, 금액은 `bigint`(원 단위), FK는 `REFERENCES users(id)` / `REFERENCES games(id)`.

```sql
-- V11__board.sql : 사장님 커뮤니티 게시판 (posts, comments, post_likes, post_reports, post_views)
-- 스타일 기준: V5__game.sql (bigserial PK, timestamptz, text + CHECK로 enum, users/games 참조)
-- 주의: 기존 마이그레이션 수정 금지. V8/V9/V10은 게임 작업 예약.

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
    FROM comments WHERE id = NEW.parent_id FOR SHARE;

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
```

---

## 3. 한국어 검색 전략 (title / content)

### 확장 가용성 확인 (정직 표시)
- 우리 이미지: `docker-compose.yml`의 `postgis/postgis:16-3.5`.
- `postgis/postgis`는 **공식 `postgres` 이미지 기반**이며, 공식 postgres 이미지는 contrib 모듈(`pg_trgm`, `btree_gin`, `unaccent` 등)을 포함한다. `pg_trgm`은 PostgreSQL 13+에서 `trusted = true`라 DB 소유자 권한으로 `CREATE EXTENSION`이 된다.
- **미검증**: 로컬 Docker 데몬이 꺼져 있어 컨테이너에 직접 접속해 `SELECT * FROM pg_available_extensions WHERE name='pg_trgm';` 를 실행하지는 못했다. 근거는 (a) postgis/postgis README의 "based on the official postgres image", (b) 공식 postgres 이미지가 contrib를 포함한다는 문서, (c) 공개된 postgis 3.5 컨테이너 사례에서 `pg_trgm 1.6`이 설치된 출력. **구현 TASK의 완료 조건에 아래 검증 명령을 반드시 넣을 것.**
  ```bash
  docker compose up -d
  docker compose exec db psql -U "$DB_USERNAME" -d seoul_startup_sim \
    -c "select name, default_version from pg_available_extensions where name='pg_trgm';"
  ```
  비어 있으면 V11에서 `CREATE EXTENSION pg_trgm`을 빼고 3-안 B(ILIKE만) 또는 C(tsvector)로 내려간다. 이 때문에 `CREATE EXTENSION IF NOT EXISTS pg_trgm;`을 V11 **맨 위 한 줄**로 격리해 두었다(실패 시 그 줄만 제거).

### 세 가지 안 비교
| 안 | 구현 | 장점 | 단점 | 한국어 적합성 |
|---|---|---|---|---|
| **A. ILIKE + pg_trgm GIN** (채택) | `title ILIKE '%'||:kw||'%'`, `CREATE INDEX ... USING gin (title gin_trgm_ops)` | 쿼리가 단순(JPA/QueryDSL에서 그대로), 형태소 분석기 없이 **부분 일치**가 된다. 2글자 이상이면 trigram 인덱스를 탄다 | 인덱스가 크다(본문 trgm은 content 길이에 비례), 1글자 검색은 인덱스를 못 탄다(`%키%` 같은 1~2자는 순차 스캔 가능), 랭킹(관련도) 없음 | **좋다.** 한국어는 공백 분절이 약해 `tsvector`의 `simple` 파서가 "창업한다" / "창업" 을 다른 토큰으로 본다. trigram은 어미 변화를 부분일치로 흡수한다 |
| **B. ILIKE만 (인덱스 없음)** | 같은 쿼리, 인덱스 없음 | 0비용, 확장 불필요 | 글 수가 수천 건을 넘으면 매 검색이 순차 스캔 | 과제 규모(수백~수천 행)에서는 **실제로 충분**하다. 폴백 안 |
| **C. tsvector + GIN** | `search_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', title||' '||content)) STORED` | 관련도 랭킹(`ts_rank`), 인덱스가 작고 빠름 | 한국어 형태소 분석기(`mecab-ko`, `pgroonga`)가 **이 이미지에 없다**. `simple`/`english` 설정은 한국어를 공백 단위로만 쪼개 "창업"으로 "창업자"를 못 찾는다. 분석기를 넣으려면 커스텀 이미지 빌드 필요 → AGENTS.md "이미지 변경" 범위 밖 | **나쁘다**(분석기 없이는) |

**결론: A를 채택하고 B를 폴백으로 둔다.** C는 "나중에 pgroonga/mecab-ko 이미지로 갈 때" 후보로만 기록.

검색 구현 규칙
- 검색 타입 4종(API 합의): `title`, `content`, `titleContent`, `author`.
  - `title`: `title ILIKE '%'||:kw||'%'` → `ix_posts_title_trgm`
  - `content`: `content ILIKE ...` → `ix_posts_content_trgm`
  - `titleContent`: `(title ILIKE ... OR content ILIKE ...)` → 두 GIN 인덱스 BitmapOr
  - `author`: `users.nickname ILIKE ...` 로 조인. 닉네임 인덱스는 users 테이블이라 V11에서 건드리지 않는다(game 패키지/기존 테이블 수정 금지). 작성자 검색은 글 수가 적어 조인 후 필터로 충분. 필요해지면 별도 마이그레이션에서 `users(nickname)` trgm 인덱스 추가를 제안.
- 키워드는 2자 이상만 허용(`VALIDATION_FAILED`), `%`/`_` 는 서버에서 이스케이프.

---

## 4. 목록 쿼리 ↔ 인덱스 매핑

| 화면/파라미터 | 쿼리 형태 | 타는 인덱스 |
|---|---|---|
| 카테고리 최신순 (기본) | `WHERE status='ACTIVE' AND category=? ORDER BY created_at DESC, id DESC` | `ix_posts_category_created` |
| 전체 최신순 | `WHERE status='ACTIVE' ORDER BY created_at DESC, id DESC` | `ix_posts_created` |
| 인기순 `sort=popular` | `ORDER BY like_count DESC, id DESC` | `ix_posts_category_likes` |
| 조회순 `sort=views` | `ORDER BY view_count DESC, id DESC` | `ix_posts_category_views` |
| 댓글순 `sort=comments` | `ORDER BY comment_count DESC, id DESC` | `ix_posts_category_comments` |
| RECORD 업종 필터 | `WHERE category='RECORD' AND status='ACTIVE' AND service_code=?` | `ix_posts_record_service` |
| RECORD 상권 필터 | `... AND area_code=?` | `ix_posts_record_area` |
| RECORD 수익 랭킹 | `WHERE category='RECORD' ... ORDER BY total_profit DESC` | `ix_posts_record_profit` |
| 내 글 | `WHERE author_id=? ORDER BY created_at DESC` | `ix_posts_author` |
| 공지 고정 | `WHERE is_pinned AND status='ACTIVE'` 를 별도 쿼리로 조회해 목록 위에 붙인다 | `ix_posts_pinned` |
| 관리자 숨김/신고 | `WHERE status='HIDDEN'`, `post_reports WHERE status='PENDING'` | `ix_posts_status`, `ix_post_reports_status` |
| 루트 댓글 페이징 | `WHERE post_id=? AND parent_id IS NULL ORDER BY created_at, id` | `ix_comments_post_root` |
| 대댓글 | `WHERE parent_id=? ORDER BY created_at, id` | `ix_comments_parent` |

- 모든 정렬에 `id DESC`를 tie-breaker로 넣는다(페이징 중복/누락 방지). 정렬 키가 같은 행이 많은 `like_count=0` 구간에서 특히 중요.
- 모든 목록 인덱스를 `WHERE status='ACTIVE'` 부분 인덱스로 만들어 삭제·숨김 글을 인덱스에서 제외했다(크기 절약 + soft delete가 기본 필터라는 의미 표현).
- 페이징은 Spring `Pageable`(offset) 사용. offset이 깊어지면 느려지지만 과제 규모에서 문제 없다. 무한스크롤로 가면 `(created_at, id) < (?, ?)` 커서 방식으로 전환 가능(같은 인덱스 재사용).
- `COUNT(*)` 총건수는 카테고리별 부분 인덱스로 index-only scan에 가깝게 처리된다.

---

## 5. 동시성 노트

### 5.1 좋아요 카운터
원칙: **멤버십 테이블이 진실, `posts.like_count`는 캐시**. 동시 요청에서도 두 값이 어긋나지 않게 "삽입이 실제로 일어났을 때만" 카운터를 올린다.

```sql
-- 좋아요 (PUT /api/posts/{id}/likes, 멱등)
WITH ins AS (
  INSERT INTO post_likes (post_id, user_id) VALUES (:postId, :userId)
  ON CONFLICT (post_id, user_id) DO NOTHING
  RETURNING 1
)
UPDATE posts p SET like_count = like_count + (SELECT count(*) FROM ins)
WHERE p.id = :postId AND EXISTS (SELECT 1 FROM ins);

-- 취소 (DELETE, 멱등)
WITH del AS (
  DELETE FROM post_likes WHERE post_id = :postId AND user_id = :userId RETURNING 1
)
UPDATE posts p SET like_count = like_count - (SELECT count(*) FROM del)
WHERE p.id = :postId AND EXISTS (SELECT 1 FROM del);
```
- `UPDATE ... SET like_count = like_count + 1` 은 행 단위 락 안에서 읽고 쓰므로 **lost update가 없다**. 반대로 `SELECT like_count` → 애플리케이션에서 +1 → `UPDATE ... SET like_count = :v` 는 유실된다. JPA 엔티티 더티 체킹으로 카운터를 올리면 후자가 되므로 **카운터는 JPQL/native `@Modifying` 업데이트로만 변경**한다.
- 같은 사용자가 따닥 두 번 누르면 UNIQUE 위반 → `ON CONFLICT DO NOTHING` 이 0행 → 카운터 안 변함 → 응답 204. API는 멱등(합의).
- **중요**: 좋아요가 `posts` 행을 UPDATE하므로 `version`이 바뀌면 글 수정 중 낙관적 락 충돌이 유발된다. → 카운터 UPDATE는 `version`을 **건드리지 않는다**(native 쿼리로 `like_count`만 UPDATE, `@Version` 증가 금지). 이 규칙을 API/구현 지시서에 명시해야 한다.
- `CHECK (like_count >= 0)`이 있어 카운터 버그가 음수로 조용히 흐르지 않고 터진다(의도).
- 정합성 보정: 관리자용 재계산 쿼리를 문서에 남긴다.
  ```sql
  UPDATE posts p SET like_count = (SELECT count(*) FROM post_likes l WHERE l.post_id = p.id);
  ```

### 5.2 댓글 카운터
```sql
INSERT INTO comments (...) VALUES (...);          -- 트리거가 depth 검증
UPDATE posts SET comment_count = comment_count + 1 WHERE id = :postId;      -- 같은 트랜잭션
UPDATE comments SET reply_count = reply_count + 1 WHERE id = :parentId;     -- 대댓글이면
```
- 집계 대상 정의: **`comment_count`는 ACTIVE + DELETED(자리 남은 삭제 댓글)를 포함하지 않는다. ACTIVE만 센다.** 즉 삭제 시 `-1`, 숨김 시 `-1`. 목록의 "댓글 n" 과 상세의 실제 보이는 댓글 수를 맞추기 위한 선택.
  - 트레이드오프: "삭제된 댓글입니다" 자리는 화면에 남는데 카운트에는 없다 → 상세 화면 표시 수와 1~2 차이. 대안은 "DELETED 포함"인데 그러면 빈 글에 "댓글 3"이 뜬다. **ACTIVE만** 채택.
- 삭제/숨김은 같은 트랜잭션에서 `comment_count` 감소. 트리거로 자동화하는 대안은 9장.

### 5.3 조회수
정책(채택): **같은 사용자 + 같은 글 + 같은 날(KST) = 1회**
```sql
WITH ins AS (
  INSERT INTO post_views (post_id, user_id, viewed_on)
  VALUES (:postId, :userId, (now() AT TIME ZONE 'Asia/Seoul')::date)
  ON CONFLICT DO NOTHING RETURNING 1
)
UPDATE posts p SET view_count = view_count + 1
WHERE p.id = :postId AND EXISTS (SELECT 1 FROM ins);
```
- 새로고침 연타로 숫자가 뛰지 않고, `post_views`가 "누가 읽었나" 데이터로도 쓰인다.
- 비용: 조회마다 INSERT 시도 1회. 과제 트래픽에서 문제 없음.
- 토큰이 없는 완전 비로그인 조회는 `user_id`가 없어 집계 불가 → **조회수를 올리지 않는다**(게스트도 `X-Guest-Token`이 있으면 user_id가 있으므로 대부분 집계된다).
- 조회수 UPDATE도 `version`을 건드리지 않는다(5.1과 같은 이유).
- 상세 조회는 읽기 트랜잭션인데 UPDATE가 끼므로, 조회수 증가는 **별도 트랜잭션**(또는 `REQUIRES_NEW`)으로 분리해 본문 조회 실패와 묶이지 않게 한다.

### 5.4 낙관적 락 (글/댓글 수정)
- `posts.version`, `comments.version` + JPA `@Version`. 수정 요청에 클라이언트가 `version`을 보낸다(API 합의).
- `UPDATE posts SET title=?, content=?, version=version+1, updated_at=now() WHERE id=? AND version=?` → 0행이면 `409 POST_VERSION_CONFLICT` / `409 COMMENT_VERSION_CONFLICT`.
- 카운터(좋아요/조회/댓글 수)는 version을 증가시키지 않는다. 그래야 "누가 좋아요 눌러서 내 수정이 실패"하지 않는다.

---

## 6. soft delete 의미

| 상태 | 누가 | 목록 | 상세 | 비고 |
|---|---|---|---|---|
| `ACTIVE` | 기본 | 보임 | 보임 | |
| `HIDDEN` | 관리자(신고 처리 `RESOLVED`) | 안 보임 | 공개 REST는 **작성자 포함 404**. 관리자(`/admin`)만 상세 조회. 작성자는 `/me/posts`(내 글 목록)에서 HIDDEN 상태만 확인 | `hidden_at` 기록. 관리자 `restore`로 ACTIVE 복구 가능 |
| `DELETED` | 작성자 또는 관리자 | 안 보임 | 404 (모두) | `deleted_at` 기록, 행은 남김. **복구 불가**(API에 restore 없음). 행 보존은 감사·FK 무결성 목적 |

(위 노출 규칙은 API 담당과 합의한 문구. `restore`는 HIDDEN → ACTIVE만.)

- 물리 삭제를 하지 않는 이유: 댓글/좋아요/신고가 FK로 매달려 있고, 신고 이력이 남아야 관리자 화면이 성립한다. FK에 `ON DELETE CASCADE`를 **일부러 넣지 않았다**(실수로 글을 지울 때 댓글까지 사라지는 걸 막는다).
- **댓글이 달린 댓글(루트) 삭제**: 루트 댓글을 삭제해도 행과 `parent_id` 사슬은 유지하고 `status='DELETED'`로만 바꾼다. 응답에서 `content`를 공통 자리표시자 `"표시할 수 없는 댓글입니다."`로 치환하고 `authorNickname`도 숨긴다(DELETED·HIDDEN 모두 같은 문구를 써서 어느 쪽인지 추측되지 않게 한다)(치환은 **애플리케이션 책임**. DB의 `content`는 감사용으로 원문 보존).
  - **ACTIVE 대댓글이 하나도 없으면 응답에서 아예 제외한다**(자리표시자도 안 보낸다). ACTIVE 답글이 남아 있을 때만 자리표시자로 노출. API 담당과 합의 완료.
  - 관리자 숨김(HIDDEN) 루트 댓글도 같은 규칙·같은 문구를 쓴다.
  - 대댓글 삭제는 그냥 숨기고 목록에서 제외(`reply_count` -1).
- 글 삭제 시 댓글은 상태를 바꾸지 않는다(글 자체가 404라 노출 경로가 없다). 복구 가능.

---

## 7. RECORD(엔딩 인증글) 규칙 - DB에서 강제하는 범위

DB에서 강제 (V11에 포함)
1. **RECORD만 게임 첨부 가능 / RECORD는 첨부 필수**: `ck_posts_record_game`.
2. **스냅샷 필수**: `ck_posts_record_snapshot` (ending_type, service_code, area_code, survived_quarters, total_profit NOT NULL). 필터·정렬이 NULL 때문에 깨지는 걸 막는다.
3. **게임당 RECORD 1건**: `ux_posts_record_game` = `UNIQUE(game_id) WHERE category='RECORD' AND status<>'DELETED'`.
   - 삭제(DELETED) 후 재작성 허용, 신고 숨김(HIDDEN)은 재작성 불가(어뷰징 방지). API 담당과 같은 문장으로 합의.
   - 위반 → `409 RECORD_ALREADY_POSTED`.
4. FK `game_id → games(id)`: 존재하지 않는 게임 첨부 불가.

DB로 강제하지 않고 서비스에서 검증 (이유 포함)
5. **`games.status='ENDED'` 여야 한다** → `409 RECORD_GAME_NOT_ENDED`.
6. **`games.user_id = 작성자`여야 한다** → `403 FORBIDDEN`.
   - 두 조건은 다른 테이블 값이라 CHECK로 못 쓴다. 선택지:
     - (채택) **서비스 검증 + 선택적 BEFORE INSERT 트리거 이중 방어**. 트리거는 `SELECT status, user_id FROM games WHERE id = NEW.game_id` 로 검사. 게임이 끝난 뒤 상태가 되돌아가는 흐름이 없으므로 트리거로 충분하고, game 패키지 코드는 건드리지 않는다(읽기만).
     - (대안) 서비스 검증만. 단순하지만 다른 경로(관리자 SQL, 배치)에서 뚫린다.
   - 트리거를 넣을지는 열린 질문 Q3. 아래 스니펫을 V11 옵션으로 둔다.
     ```sql
     CREATE FUNCTION board_record_game_guard() RETURNS trigger AS $$
     DECLARE g_status text; g_user bigint;
     BEGIN
       IF NEW.category <> 'RECORD' THEN RETURN NEW; END IF;
       SELECT status, user_id INTO g_status, g_user FROM games WHERE id = NEW.game_id;
       IF g_status IS DISTINCT FROM 'ENDED' THEN
         RAISE EXCEPTION 'game % is not ENDED', NEW.game_id USING ERRCODE = '23514';
       END IF;
       IF g_user <> NEW.author_id THEN
         RAISE EXCEPTION 'game % is not owned by user %', NEW.game_id, NEW.author_id USING ERRCODE = '23514';
       END IF;
       RETURN NEW;
     END; $$ LANGUAGE plpgsql;
     CREATE TRIGGER trg_posts_record_game_guard
       BEFORE INSERT OR UPDATE OF game_id, category, author_id ON posts
       FOR EACH ROW EXECUTE FUNCTION board_record_game_guard();
     ```
7. **스냅샷 채우는 시점**: 글 작성 시 1회, `game_records`(엔딩 시 기록) + `shops`에서 읽어 복사. 이후 게임 데이터가 바뀌어도 글은 안 바뀐다. 작성자가 스냅샷 값을 직접 보내는 걸 **허용하지 않는다**(위조 방지) → 요청 바디에는 `gameId`만, 나머지는 서버가 채운다. API 담당에게 전달 완료.
   - `ending_type`, `survived_quarters`, `total_profit`, `area_code/area_name`, `service_code/service_name` 출처: `game_records`. `game_records`가 아직 없는 게임(구버전)이면 `games`+`shops`+`turn_results` 집계로 폴백하되, **미확인**: `game_records` 적재 시점이 TASK-004 리팩터링 범위와 겹칠 수 있어 구현 전 확인 필요(Q4).

---

## 8. 신고 → 숨김 흐름 (DB 관점)
1. 사용자 신고: `INSERT INTO post_reports (...) ON CONFLICT (target_type,target_id,reporter_id) DO NOTHING` → 0행이면 `409 ALREADY_REPORTED`.
2. 자기 글 신고 금지는 서비스 검증(대상 작성자 조회 필요).
3. 관리자(`/admin`, Thymeleaf 세션): `status='PENDING'` 목록 → 처리.
   - 숨김(ACCEPT): `UPDATE posts SET status='HIDDEN', hidden_at=now() WHERE id=? AND status='ACTIVE'` + `UPDATE post_reports SET status='RESOLVED', handled_at=now(), handled_by=? WHERE ...`
   - 반려: `status='DISMISSED'`.
   - 같은 대상의 PENDING 신고들을 한 번에 같은 상태로 바꾼다(대상 단위 처리).
4. `ck_post_reports_handled`: PENDING이 아니면 `handled_at`이 반드시 있어야 한다 → 처리 이력 누락 방지.

---

## 9. 대안 기록 (의견이 갈릴 수 있는 지점)

| 주제 | 채택안 | 대안 | 비교 |
|---|---|---|---|
| 신고 대상 참조 | `target_type` + `target_id` (polymorphic, FK 없음) | `post_id` / `comment_id` 두 nullable 컬럼 + XOR CHECK + 각각 FK | 채택안: 테이블 1개로 관리자 화면이 단순, API 담당과 합의된 이름. 단 **FK 무결성 없음**(지워진 대상 id가 남을 수 있음 - 우리는 물리 삭제를 안 하므로 실제 위험 낮음). 대안: FK로 무결성 확보, 쿼리에 `COALESCE`/분기 필요. 메인이 무결성을 더 중시하면 대안으로 교체 가능(스키마 변경 영향 작음) |
| 상태 표현 | `status` enum(ACTIVE/HIDDEN/DELETED) 단일 진실 + `hidden_at`/`deleted_at` 감사 컬럼 | 기획 담당 1차 안: `deleted_at IS NULL` + `is_hidden` boolean | 채택안: 상태가 3개로 배타적이라 "숨김이면서 삭제" 같은 모순이 안 생기고 부분 인덱스 조건이 하나(`status='ACTIVE'`)로 단순. 대안: Spring Data `@Where`/Hibernate soft delete 관례에 가깝고 ORM 코드가 짧다. 두 안 모두 타임스탬프를 남기므로 채택안에서 `deleted_at`을 그대로 제공해 호환시켰다 |
| 카운터 유지 | 애플리케이션 트랜잭션 안에서 원자적 UPDATE | DB 트리거로 자동 유지 | 채택안: 흐름이 코드에서 보이고 테스트가 쉽다. 과제 평가에서 "서비스 계층 트랜잭션"을 보여주기도 좋다. 대안: 어떤 경로로 들어와도 정합. 단 디버깅이 어렵고 대량 삽입에서 느리다 |
| 댓글 깊이 제한 | `depth` 컬럼 + CHECK + BEFORE INSERT 트리거 | 서비스에서만 검사 | 채택안: 깊이 2가 **DB에 들어올 수 없다**(설계 의도가 스키마에 남는다). 대안: 트리거 없어 단순하지만 버그/직접 SQL로 뚫린다 |
| 검색 | ILIKE + pg_trgm GIN | tsvector(한국어 분석기 없음) / ILIKE only | 3장 표 참고 |
| 게시판 공지 | `posts.is_pinned` (카테고리 무관, 관리자만 설정) | 기존 `notices` 테이블 재사용 | 채택안: 공지도 댓글·조회수가 붙는 "글"이라 posts가 맞다. `notices`는 패치노트/운영 공지(게임 화면 배너)로 역할 분리. 둘을 합치면 목록 UNION이 필요해진다 |

---

## 10. 범위 제외 (기획 담당 합의)
- 이미지 업로드: MVP 제외. 넣게 되면 `post_attachments(id, post_id, url, mime, size, sort_order, created_at)` 별도 마이그레이션.
- 동네 반응(turn_results.reactions) 답글: MVP 제외. 도입 시 `reaction_replies(game_id, turn, reaction_idx, choice_code, text, created_at)` 로 **선택 코드를 기록**해 재계산 결정론(시드+선택+데이터 버전)을 유지한다. 자유 텍스트는 게임 수치에 반영하지 않는다.

---

## 11. API 담당(Codex)·기획 담당(Gemini)과 합의된 이름

테이블: `posts`, `comments`, `post_likes`, `post_reports`, `post_views`(DB 내부용, API 노출 없음)

enum (모두 대문자 `text` + CHECK)
- `posts.category`: `FREE`, `TIP`, `QNA`, `RECORD`
- `posts.status`, `comments.status`: `ACTIVE`, `HIDDEN`, `DELETED`
- `post_reports.target_type`: `POST`, `COMMENT`
- `post_reports.reason`: `SPAM`, `ABUSE`, `ADULT`, `ETC`
- `post_reports.status`: `PENDING`, `RESOLVED`, `DISMISSED` (기획 담당 안으로 통일)
- API 쿼리 파라미터: `sort` = `latest|popular|views|comments`, `searchType` = `title|content|titleContent|author`

컬럼명 합의
- 누적 순이익은 DB `total_profit` ↔ API `totalProfit` (계약 3장과 동일 표기, `game_records.total_profit`과 같은 이름).
- 스냅샷 7종: `ending_type`, `service_code/service_name`, `area_code/area_name`, `shop_name`, `owner_name`, `survived_quarters`, `total_profit` ↔ `endingType`, `serviceCode/serviceName`, `areaCode/areaName`, `shopName`, `ownerName`, `survivedQuarters`, `totalProfit` (기획 담당 요청으로 shop_name/owner_name 추가)
- `post_reports.handled_by`는 `text`(관리자 username), API 미노출
- `survived_quarters` ↔ `survivedQuarters`, `service_code`/`area_code`는 DB·API 동일 의미.
- `comments.version` 추가(댓글도 낙관적 락).

에러 코드 (공통 재사용 + 신규)
- 재사용: `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 NOT_FOUND`, `400 VALIDATION_FAILED`
- 신규: `409 POST_VERSION_CONFLICT`, `409 COMMENT_VERSION_CONFLICT`, `409 ALREADY_REPORTED`, `409 RECORD_ALREADY_POSTED`, `409 RECORD_GAME_NOT_ENDED`, `400 COMMENT_DEPTH_EXCEEDED`
- 게임 비소유 → `403 FORBIDDEN`, RECORD인데 `gameId` 미첨부 → `400 VALIDATION_FAILED`
- 좋아요는 PUT/DELETE 204 멱등 → `ALREADY_LIKED`/`NOT_LIKED` 코드 **폐기**

정책 문구 합의 (양쪽 문서 동일 표기)
- 자리표시자 문구: `"표시할 수 없는 댓글입니다."` (DELETED/HIDDEN 공통, ACTIVE 답글이 있을 때만 노출)
- 글 `HIDDEN`: 공개 REST는 작성자 포함 404, 작성자는 `/me/posts`에서 상태만 확인, 관리자만 상세. `DELETED`는 복구 불가(restore는 HIDDEN만).
- 자기 글/댓글 신고는 `403 FORBIDDEN`, 같은 대상의 PENDING 신고는 처리 시 일괄 종결(RESOLVED/DISMISSED).

길이 제한 (API 담당 제안 = DB CHECK 반영)
- `posts.title` 1~100, `posts.content` 1~10000, `comments.content` 1~1000, `post_reports.detail` ≤500
- DB CHECK와 Bean Validation을 **같은 값**으로 유지. 정상 경로는 `@Valid`에서 400으로 걸리고, CHECK는 최후 방어선.

---

## 12. 열린 질문 (메인 결정 필요)

1. **Q1 `post_views` 보존**: 글마다 사용자×날짜 행이 쌓인다. 과제 규모면 방치해도 되지만 정리 정책(예: 90일 이전 삭제 배치)을 둘지? 대안: 조회 중복 방지를 DB 대신 캐시/세션으로 하고 테이블을 없애기(간단하지만 서버 재시작 시 초기화, 통계 불가).
2. **Q2 신고 대상 참조 방식**: polymorphic(`target_type`+`target_id`, 채택) vs nullable FK 2컬럼. FK 무결성을 원하면 바꿔야 함 (9장).
3. **Q3 RECORD 게임 검증 트리거**를 V11에 넣을지(이중 방어) 서비스 검증만 할지. 트리거는 `games`를 읽기만 하므로 "game 패키지 수정 금지"와 충돌하지 않는다고 판단했으나, Flyway에서 game 테이블에 의존성이 생기는 점은 메인 판단 필요.
4. **Q4 `game_records` 적재 보장 (미확인)**: RECORD 스냅샷 출처를 `game_records`로 잡았는데, 엔딩 시 이 테이블에 실제로 적재되는지 TASK-004 리팩터링 현황을 확인하지 못했다. 적재가 없다면 `games` + `shops` + `turn_results` 집계로 스냅샷을 만들어야 한다(쿼리 1개 추가).
5. **Q5 `comment_count` 정의**: ACTIVE만 집계(채택) vs DELETED 포함. 화면 표시와 1~2 차이가 생기는 지점(5.2).
6. **Q6 pg_trgm 가용성 검증 (미검증)**: 3장. 구현 TASK 완료 조건에 `pg_available_extensions` 확인 명령을 넣어야 한다. 없으면 GIN 인덱스 2개와 `CREATE EXTENSION` 한 줄을 제거(ILIKE만으로 동작).
7. **Q7 게스트 닉네임 노출**: 작성자 표시를 `users.nickname`으로 하면 게스트 닉네임이 중복될 수 있다. 글에 `author_nickname` 스냅샷 컬럼을 둘지(작성 시점 닉네임 고정) vs 항상 조인(닉네임 변경이 과거 글에 반영). 현재는 **조인** 전제(스냅샷 컬럼 없음).
8. **Q8 공지 고정 범위**: `is_pinned` 글을 카테고리별로 고정할지 전체 상단 고정할지. 현재 인덱스는 둘 다 지원.
