# 게시판 설계 보안·일관성 리뷰 (Claude) — 2026-11-18

대상: `docs/api/API_CONTRACT.md` 4장(v1.2), `docs/DESIGN.md` 19장, `docs/board/V11__board.sql`, `docs/tasks/TASK-005A/B/C.md`, `docs/board/DEMO_SCENARIO.md`
역할 분담: SecurityFilterChain/CSRF 설정·CORS·Vite 프록시·`GuestAuth` 예외 경계는 구현 가능성 리뷰(Codex) 문서, 시연 흐름·시간 배분·평가 포인트는 평가 리뷰(Gemini) 문서에서 다룬다. 여기서는 규칙·상태코드·DB 제약·동시성만 본다.
심각도: **차단**(이 상태로 구현하면 취약점·버그 확정) / **중요**(구현 전에 문서 한 줄 추가 필요) / **사소**(문구·성능·주석)

## 요약 (차단 4, 중요 9, 사소 7)

| # | 심각도 | 한 줄 |
| --- | --- | --- |
| B1 | 차단 | 좋아요 `PUT/DELETE`에 대상 공개 여부 규칙이 없다 → HIDDEN/DELETED 글에 좋아요·카운터 조작 가능 |
| B2 | 차단 | 401/404/403/409 **판정 순서**가 문서마다 다르다 → HIDDEN 글에서 403이 새면 존재·소유 정보 노출 |
| B3 | 차단 | LIKE 이스케이프 범위가 `%`, `_`뿐이다 → 역슬래시·`ESCAPE` 절 미지정으로 리터럴 검색 계약이 깨진다 |
| B4 | 차단 | 관리자 숨김·복구 시 `comment_count`/`reply_count` 재계산 규칙이 없다 → 카운터가 계약(ACTIVE 합계)과 영구 불일치 |
| I1 | 중요 | 카운터·조회수 UPDATE가 `updated_at`을 건드리지 않는다는 문장이 없다 |
| I2 | 중요 | RECORD 첨부에서 "남의 게임 403"은 순차 `gameId` 열거로 타인 게임 존재를 알려준다 |
| I3 | 중요 | 신고 API의 404/403/409 판정 순서 미정 → 비공개 대상·타인 글 여부가 코드로 샌다 |
| I4 | 중요 | 게스트 토큰 무제한 발급 → 좋아요·조회수·신고 부풀리기. 최소 대책(429)이 계약에 없다 |
| I5 | 중요 | `/share/{gameId}` OG 메타·링크 속성에 인증글 제목을 넣는 XSS 경로가 `th:text` 금지 규칙에서 빠져 있다 |
| I6 | 중요 | 관리자 조치 ↔ 사용자 수정 동시 실행 시 허용 전이표와 잠금 순서가 없다(데드락·전이 충돌) |
| I7 | 중요 | 관리자 신고 일괄 처리의 잠금 순서 미지정 → 관리자 2명 동시 처리 시 데드락 |
| I8 | 중요 | `reply_count`/`comment_count` 감소에 음수 방어가 없어 CHECK 위반(23514)이 500으로 샐 수 있다 |
| I9 | 중요 | RECORD 차단 기간(005B 전) 동작이 계약·시연·프론트에서 서로 다르다 |
| M1~M7 | 사소 | `hidden_at` CHECK, 트리거 `search_path`, q 최소 길이, 고정 글 정렬 인덱스/주석, 상세 조회의 쓰기 트랜잭션, 코드 형식 검증, 토큰 로깅 금지 |

---

## 1. 보안

### B1 (차단) 좋아요 엔드포인트에 대상 공개 여부 규칙이 없다
근거: `docs/api/API_CONTRACT.md:284-285` (좋아요 절 전체에 404/권한 문장 없음), `docs/api/API_CONTRACT.md:185` (일반 규칙은 "글/댓글 수정·삭제"와 "조회" 맥락), `docs/tasks/TASK-005A.md:23` (행 수 기반 카운터만), `docs/board/V11__board.sql:154-160` (`post_likes`에 글 상태 조건 없음)

문제: 좋아요만 `postId`에 대한 상태 검사 규칙이 빠졌다. 그대로 구현하면 HIDDEN(신고 숨김) 또는 DELETED 글에도 `PUT /like`가 204를 반환하고 `like_count`가 올라간다. 즉 (a) 숨김 글의 존재 여부가 204/404 차이로 노출되고, (b) 숨김 글이 복구되면 조작된 좋아요 수가 그대로 살아나 `sort=popular` 상위를 차지한다. `post_likes`는 FK만 있어 DB도 막지 못한다.

수정 문구 (API 4장 "좋아요" 절 끝에 추가):
> - 대상 글이 공개(ACTIVE)가 아니면(없음·HIDDEN·DELETED) 404 `NOT_FOUND`이며 `post_likes` 행과 `like_count`를 변경하지 않는다. 작성자 자신의 HIDDEN 글도 같다. 공개 확인·행 삽입/삭제·카운터 갱신은 한 트랜잭션에서 수행한다.

TASK-005A.md "할 것 3"에 한 줄 추가: `좋아요·신고도 대상 공개 확인 후 처리(비공개 404)`. 테스트 목록(`TASK-005A.md:37` 옆)에 `HIDDEN 글 좋아요 404 + like_count 불변` 추가.

### B2 (차단) 상태코드 판정 순서가 문서마다 다르다
근거: `docs/api/API_CONTRACT.md:231` ("인증 → 대상과 작성자 확인 → version"), `docs/api/API_CONTRACT.md:185` (숨김·삭제·경로 불일치 404), `docs/api/API_CONTRACT.md:311` (관리자 조치 시 version 증가), `docs/tasks/TASK-005A.md:21,26` ("0행이면 존재·권한 확인 후 409"), `docs/DESIGN.md:421-422`

문제: 세 문서가 "작성자 확인 먼저"(API 231), "HIDDEN은 작성자도 404"(005A 21), "version 0행이면 409"(005A 26)를 각자 말하는데 조합 순서가 없다. 구현자가 소유권을 먼저 보면 **타인의 HIDDEN 글에 403이 나가고**(글이 존재하며 남의 글이라는 사실이 노출), version을 먼저 보면 관리자가 숨긴 직후 사용자에게 409가 나가 "다시 불러오기"를 무한 반복한다(재조회는 404).

수정 문구 (API 4장 "공통"에 새 줄로 넣고 `:231`은 이 줄을 참조하게 고친다):
> - 글·댓글의 모든 쓰기 경로(수정/삭제/좋아요/신고/댓글 작성)는 **401 → 404 → 403 → 409** 순서로 판정한다. ① 인증 필요한데 토큰 누락·무효면 401. ② 대상이 없거나 ACTIVE가 아니거나(HIDDEN/DELETED, 작성자 본인 포함) 경로 소속이 다르면 404. ③ 공개 대상이지만 작성자가 아니면 403. ④ 그 다음에만 `version` 조건부 갱신 결과로 409. 조건부 UPDATE가 0행이면 대상을 재조회해 ②·③을 다시 적용한 뒤(관리자 조치로 HIDDEN이 되었으면 404) 남는 경우에만 409를 반환한다.

### B3 (차단) LIKE 이스케이프 범위·방법이 부족하다
근거: `docs/api/API_CONTRACT.md:192` ("`%`, `_`도 문자"), `docs/board/drafts/DB_DRAFT.md:297` ("키워드 2자 이상, `%`/`_`는 서버에서 이스케이프"), `docs/tasks/TASK-005A.md:34` (테스트는 `%`·`_`만)

문제: PostgreSQL `ILIKE`의 기본 escape 문자는 역슬래시다. `%`, `_`만 `\%`, `\_`로 바꾸면 사용자가 입력한 `\`가 이스케이프 문자로 해석돼 `\` 검색이 어긋나고, `\%` 같은 입력은 와일드카드를 되살린다. 어느 문서도 `ESCAPE` 절을 명시하지 않았다.

수정 문구 (API 4장 목록 절 `q` 설명에 추가):
> - 서버는 `q`에서 `\` → `\\`, `%` → `\%`, `_` → `\_` 순서로 치환한 뒤 `title ILIKE ? ESCAPE '\'` 형태로 바인딩 파라미터로만 넘긴다. 패턴 문자열은 SQL에 직접 이어 붙이지 않는다.

테스트 추가(`TASK-005A.md:34`): `q`가 `%`, `_`, `\`, `50%_할인`일 때 리터럴 일치 결과만 나오는지 각각 검증.

정렬 인젝션은 현재 설계가 맞다(`API_CONTRACT.md:193,324`, `TASK-005A.md:18`: 화이트리스트 enum → `Pageable` 조립). 한 줄만 강하게: `sort`·`searchType`·`category`는 **enum 바인딩**으로 받고 매칭 실패는 400, 컬럼명 문자열은 enum→상수 매핑 테이블에서만 가져온다.

### B4 (차단) 관리자 숨김·복구 시 카운터 재계산 규칙이 없다
근거: `docs/api/API_CONTRACT.md:274` (`commentCount`는 ACTIVE 루트·대댓글 합계), `docs/api/API_CONTRACT.md:311` (상태 변경 시 version 증가만 언급), `docs/tasks/TASK-005A.md:24` (댓글 **작성·삭제**만 카운터 갱신), `docs/board/V11__board.sql:33,97`

문제: 관리자가 댓글을 HIDDEN 처리하거나 복구할 때 `posts.comment_count`와 부모 `comments.reply_count`를 어떻게 할지 아무 문서에도 없다. 계약은 "ACTIVE 합계"라고 못 박았으므로 숨김 후에도 숫자가 그대로면 목록·상세의 댓글 수가 영구히 틀린다(시연에서 바로 보인다). 글 숨김 시 `like_count`/`view_count`는 보존이 맞지만 그것도 명시가 없다.

수정 문구 (API 4장 관리자 절 `:311` 끝에 추가):
> - 댓글 숨김/복구/삭제는 같은 트랜잭션에서 `posts.comment_count`와 (대댓글이면) 부모 `comments.reply_count`를 ACTIVE 기준으로 ∓1 한다. 글 숨김/복구는 `like_count`·`view_count`·`comment_count`를 바꾸지 않는다(복구 시 그대로 복원). 루트 댓글 숨김은 대댓글 상태를 바꾸지 않으며, 공개 대댓글이 남아 있으면 자리표시자로 노출된다.

TASK-005A 테스트에 추가: `관리자 댓글 숨김 → comment_count -1, 복구 → +1, 자리표시자 유지`.

### I1 (중요) 카운터 UPDATE와 `updated_at`
근거: `docs/api/API_CONTRACT.md:217` ("조회수와 카운터 갱신은 본문 `version`을 올리지 않는다"), `docs/board/V11__board.sql:40`, `docs/api/API_CONTRACT.md:197` (목록에 `updatedAt` 노출)

`version`만 언급하고 `updated_at`은 빠졌다. 트리거가 없으니 기본값은 유지되지만, 구현자가 `updated_at = now()`를 습관적으로 넣으면 모든 글이 조회될 때마다 "수정됨"으로 보인다.
수정 문구: `:217`의 해당 문장을 `조회수와 카운터(view/like/comment/reply) 갱신은 본문 version과 updated_at을 바꾸지 않는다. updated_at은 제목·본문·카테고리·상태 변경에서만 갱신한다.`로.

### I2 (중요) RECORD 첨부의 "남의 게임 403"은 게임 존재를 알려준다
근거: `docs/api/API_CONTRACT.md:252` (게임 없음 404 → 남의 게임 403), `docs/DESIGN.md:415`, `docs/tasks/TASK-005B.md:13`

`games.id`는 `bigserial`이라 열거가 쉽고, 403/404 차이로 "이 번호의 게임이 실제로 존재한다"가 드러난다. 게임 자체는 비공개 리소스(소유자 전용 `summary`)이므로 존재 여부를 숨기는 편이 맞다. 다만 403 시연(`DEMO_SCENARIO.md:12`)은 **글 수정·삭제**로 충분히 보여줄 수 있어 과제 가치를 잃지 않는다.

수정 문구 (API 4장 `:252`, DESIGN 19장 규칙표, TASK-005B "할 것 2"를 같은 문장으로):
> - 첨부 게임은 **소유자에게만 존재한다**. 게임이 없거나 내 게임이 아니면 둘 다 404 `NOT_FOUND`(게임 존재 여부를 구분하지 않는다). 내 게임인데 `ENDED`가 아니면 409 `RECORD_GAME_NOT_ENDED`. 403은 글·댓글 소유권 위반에만 쓴다.

유지 결정을 내릴 경우 대안 문구: `남의 게임은 403으로 둔다(게임 ID 존재 노출을 감수). 공개 경로에서는 /api/board/records/{gameId}가 204로만 답해 존재를 숨긴다`를 근거로 명시해 두면 리뷰 지적에 답할 수 있다.

### I3 (중요) 신고 API 판정 순서
근거: `docs/api/API_CONTRACT.md:292`, `docs/board/V11__board.sql:179`

"대상·소속 글이 공개여야 하며 아니면 404 / 자기 글은 403 / 중복 409"만 있고 순서가 없다. 소유자 확인을 먼저 하면 비공개 대상에서 403이 새고, 중복 확인을 먼저 하면 이미 신고한 비공개 대상에서 409가 샌다.
수정 문구: `:292`에 추가 →
> - 판정 순서는 401 → 404(대상 없음·대상 또는 소속 글이 ACTIVE 아님·`targetType`과 실제 대상 종류 불일치) → 403(내가 쓴 글·댓글) → 409 `ALREADY_REPORTED`. `targetId`는 `targetType`에 해당하는 테이블에서만 조회한다(`post_reports.target_id`에는 FK가 없다, `V11__board.sql:170`).

신고자 비노출은 현재 설계가 충분하다(`:292` 신고자·상세 비공개, `:311` `handled_by` 비노출, `V11__board.sql:177-178`). 추가로 한 줄: `신고 접수 수·신고 여부는 공개 응답(목록·상세·댓글)에 어떤 필드로도 포함하지 않는다.`

### I4 (중요) 게스트 토큰 남용 — 과제 범위의 최소 대책
근거: `docs/api/API_CONTRACT.md:17-18` (`POST /api/guest` 무제한, 제한 문장 없음), `docs/api/API_CONTRACT.md:217,285` (조회수·좋아요는 사용자 단위 1회), `docs/board/drafts/PRODUCT_DRAFT.md:189` (조회수 어뷰징 방지는 "이후")

구조적으로 `post_views` PK와 `UNIQUE(post_id,user_id)`는 **사용자 단위** 방어라서, 토큰을 무제한 발급할 수 있으면 둘 다 무력하다. 스크립트 하나로 `sort=popular` 1위와 조회수 수천을 만들 수 있고, 같은 방식으로 같은 대상에 서로 다른 계정으로 신고를 쌓을 수 있다. 다만 기말 과제 범위에서 캡차·IP 평판은 과하다. 아래 두 줄이 "인식하고 최소 대응했다"를 보여주는 선이다.

수정 문구 (API 4장 "공통"에 추가 + 에러 코드 절에 429 추가):
> - 남용 방지(최소선): 같은 클라이언트 IP 기준 `POST /api/guest` 분당 5회, 인증 사용자 기준 글 작성 분당 3회·시간당 20회, 댓글 분당 10회, 신고 분당 5회, 좋아요 변경 분당 30회를 넘으면 429 `RATE_LIMITED`(`Retry-After` 초 단위 포함). 조회(GET)와 조회수 집계는 제한하지 않는다. 구현은 인메모리 고정 창 카운터(단일 인스턴스 전제)로 하고, 분산 환경·IP 평판·캡차는 범위 밖으로 둔다.
> - 추가 에러 코드: `RATE_LIMITED`(429).

TASK-005A "할 것 3" 끝에: `게스트 생성·쓰기 경로에 인메모리 레이트 리밋 필터 1개(IP 또는 userId 키). 429 응답도 공통 에러 형식.` 테스트 1건: `같은 IP로 게스트 생성 6회 → 6번째 429`.
DESIGN 19장 "범위" 표의 "이후" 칸에 `분산 레이트 리밋, 캡차, IP 평판, 자동 블라인드`를 넣어 의도적 범위 축소임을 남긴다.

### I5 (중요) XSS — 본문은 안전, 공유 페이지 메타/속성이 구멍
근거: `docs/tasks/TASK-005A.md:29` ("Thymeleaf는 `th:text`만, `th:utext` 금지"), `docs/api/API_CONTRACT.md:183,261`, `docs/tasks/TASK-005B.md:17`, `docs/tasks/TASK-005C.md:12`

본문 저장 원문 + React 텍스트 렌더링 + `th:text` 규칙은 맞다. 남는 경로는 세 가지다.
1. `/share/{gameId}`의 **OG 메타 태그**에 인증글 제목을 넣는다(`:261` "기존 Thymeleaf/OG 계약 유지"). `<meta content>`는 `th:text`가 아니라 속성이므로 `th:attr`/`th:content`로 바인딩해야 하고, 문자열 연결로 만들면 `"` 하나로 태그를 깨뜨릴 수 있다.
2. 관리자 화면의 **플래시 메시지·검색어 되돌려주기**가 `th:utext`로 작성되면 글 제목이 그대로 HTML이 된다(`:310` PRG + 플래시).
3. React에서 `record.shareUrl`·`postUrl`을 `href`에 그대로 넣는다(`:259,201`). 지금은 서버가 만드는 상대경로지만, 규칙이 없으면 나중에 외부 값이 들어올 때 `javascript:`가 통한다.

수정 문구:
- TASK-005A.md:29를 →
> 7. **본문 출력 안전**: 저장은 원문 그대로. React는 텍스트 렌더링(`dangerouslySetInnerHTML` 금지), 줄바꿈은 CSS `white-space: pre-wrap`으로 처리한다. Thymeleaf(관리자·공유)는 `th:utext` 전면 금지, 사용자 입력이 들어가는 **속성·메타 태그·플래시 메시지**도 `th:text`/`th:attr`로만 바인딩하고 문자열 연결로 마크업을 만들지 않는다. 링크 속성에는 서버가 만든 `/board/...`·`/share/...` 상대경로만 허용한다.
- TASK-005B.md:17에 한 줄: `공유 페이지의 제목·OG 메타는 th:text/th:content로 바인딩하고 제목의 줄바꿈·제어문자는 공백으로 치환한다.`
- TASK-005C.md:22 테스트에 추가: `제목·본문에 "><img src=x onerror=1> 를 넣어도 텍스트로 보이고, shareUrl이 javascript: 면 링크를 렌더링하지 않는다.`

### CSRF (현상태 적정, 문구 1줄만 추가)
근거: `docs/api/API_CONTRACT.md:310` (`/admin/**` 전 POST CSRF, 실패 403), `docs/DESIGN.md:429-430` (쿠키 브리지를 의도적으로 거부하고 헤더 토큰 유지), `docs/api/API_CONTRACT.md:294-309` (관리자 조치는 전부 POST, GET 부작용 없음)

`/api`는 쿠키가 아닌 커스텀 헤더 인증이라 CSRF 표면이 없고, `/admin`은 세션+CSRF다. 설계상 맞다. 지키게 만들 문장만 없다.
수정 문구 (API 4장 공통): `게스트 토큰은 쿠키·`Authorization` 자동 전송 수단에 저장하지 않는다(쿠키 브리지 금지, DESIGN 19장 화면 기술). 이 전제가 깨지면 `/api`에도 CSRF 토큰이 필요해진다.`
(필터 체인 설정·CORS·프록시 포트는 Codex 리뷰 참조.)

## 2. 일관성

### I9 (중요) RECORD 차단 기간의 동작이 세 문서에서 다르다
근거: `docs/api/API_CONTRACT.md:180` (005B 전 `category: RECORD` 작성은 400 `VALIDATION_FAILED`), `docs/tasks/TASK-005A.md:8` (같음, 읽기·`record` 필드는 만든다), `docs/api/API_CONTRACT.md:257` (`/api/board/records/{gameId}`), `docs/tasks/TASK-005C.md:6,15` (프론트는 목), `docs/board/DEMO_SCENARIO.md:10,17`

빠진 것 세 개: ① `GET /api/board/records/{gameId}`가 차단 기간에 존재하지 않는다(005A 범위 아님)는 말이 없어 005C가 실서버에서 404를 받는다. ② 400 코드가 입력 오류와 같은 `VALIDATION_FAILED`라서 프론트가 "준비 중"과 "제목 누락"을 구분할 수 없다. ③ 목록 `serviceCode`/`areaCode` 필터는 열려 있으나 데이터가 없어 항상 빈 목록인데, 그게 정상임을 적어두지 않았다.

수정 문구 (API 4장 `:180` 바로 뒤):
> - 차단 기간 동작: `category: RECORD` 작성은 400 `VALIDATION_FAILED`, `message`는 고정 문구 `"엔딩 인증글은 아직 준비 중입니다."`를 사용한다. `GET /api/board/records/{gameId}`는 TASK-005B에서 처음 노출되며 그 전에는 매핑하지 않는다(프론트는 목으로 처리). RECORD 탭·업종/상권 필터는 동작하되 결과는 `totalElements: 0`이다.

TASK-005C.md:6에 한 줄: `실서버 모드에서는 /api/board/records/{gameId} 호출을 비활성화하고(005B 전 미구현), RECORD 작성 400은 서버 message를 그대로 노출한다.`

### I6 (중요) 관리자 조치 ↔ 사용자 수정의 허용 전이표가 없다
근거: `docs/api/API_CONTRACT.md:310-311` ("대상 행 잠금 후 현재 상태와 허용 전이를 확인", 전이표 없음), `docs/board/V11__board.sql:35,57,98,107`

"허용 전이"라는 말만 있고 목록이 없어서 구현자가 매번 다르게 정한다. 특히 사용자가 soft delete 한 글을 관리자가 동시에 숨기려는 경우(신고 처리 중)가 시연에서 실제로 발생한다(`DEMO_SCENARIO.md:12-13`).
수정 문구 (`:311`에 표 추가):
> | 현재 | 허용 조치 | 금지(409 폼 오류) |
> | --- | --- | --- |
> | ACTIVE | hide→HIDDEN, delete→DELETED, pin/unpin | restore |
> | HIDDEN | restore→ACTIVE, delete→DELETED | hide(동일 조치 반복은 상태·카운터 변경 없이 성공 처리), pin |
> | DELETED | 없음 | hide, restore, delete, pin |
>
> `restore`는 `hidden_at`을 NULL로 되돌린다. 신고 처리(`resolve`)에서 대상이 이미 HIDDEN이면 숨김을 다시 적용하지 않고 신고만 RESOLVED로 종결하며, 대상이 DELETED면 숨김 없이 RESOLVED로 종결한다.

### I7 (중요) 신고 일괄 처리의 잠금 순서
근거: `docs/api/API_CONTRACT.md:311` ("같은 대상의 PENDING 신고를 한 번에 처리"), `docs/board/V11__board.sql:183`

관리자 2명이 같은 대상의 서로 다른 신고 ID로 동시에 처리하면, 신고 행과 대상 행을 잡는 순서가 엇갈려 데드락이 난다.
수정 문구: `:311`에 추가 → `처리 트랜잭션은 ① 대상 글/댓글 행을 FOR UPDATE로 먼저 잠그고 ② 같은 대상의 PENDING 신고를 ORDER BY id FOR UPDATE로 잠근 뒤 ③ 상태·카운터를 갱신한다. 항상 이 순서를 지킨다. 다른 관리자가 먼저 종결했으면 추가 변경 없이 플래시로 "이미 처리됨"을 알린다.`

### I8 (중요) 카운터 감소에 음수 방어가 없다
근거: `docs/board/V11__board.sql:31-33,97` (`CHECK (… >= 0)`), `docs/tasks/TASK-005A.md:23-24`

CHECK는 정합성에는 좋지만, 중복 감소 경로가 하나라도 있으면 23514가 터져 500이 된다(TASK-005A.md:27은 23514를 `COMMENT_DEPTH_EXCEEDED` 계열로만 변환하도록 적혀 있어 더 혼란스럽다).
수정 문구 (TASK-005A.md:23-24에 추가): `감소 UPDATE는 반드시 … SET c = c - 1 WHERE id = ? AND c > 0 형태로 하고, 영향 행이 0이면 카운터가 이미 바닥이라는 뜻이므로 경고 로그만 남기고 트랜잭션은 성공시킨다. 23514를 코드로 변환할 때는 제약 이름(ck_comments_depth / ck_posts_record_*)으로 분기한다.`

### M1 (사소) `hidden_at` CHECK와 복구 시 초기화가 없다
근거: `docs/board/V11__board.sql:41-42,57,102-103,107` — `deleted_at`만 상태와 묶여 있고 `hidden_at`은 자유롭다. ACTIVE인데 `hidden_at`이 남아 있거나 HIDDEN인데 NULL인 행이 생긴다.
수정 문구(V11에 추가 가능하면, 아니면 계약에 운영 규칙으로):
`CONSTRAINT ck_posts_hidden_at CHECK ((status = 'HIDDEN') = (hidden_at IS NOT NULL) OR status = 'DELETED')` — 삭제 시 이력 보존이 필요하면 CHECK 대신 계약에 `restore는 hidden_at을 NULL로, hide는 now()로 설정하고 DELETED 전이는 hidden_at을 유지한다`를 명시. 둘 중 하나는 반드시 문서화.

### M2 (사소) 트리거 함수의 `search_path` 고정
근거: `docs/board/V11__board.sql:117-149,203-224` — `SECURITY INVOKER`라 위험은 낮지만, 스키마 섀도잉으로 `comments`/`games`가 다른 테이블로 해석될 수 있다.
수정 문구: 두 함수에 `SET search_path = pg_catalog, public` 추가(`$$ LANGUAGE plpgsql` 뒤 또는 함수 정의 옵션으로).

### M3 (사소) 검색 키워드 최소 길이가 계약과 근거 문서에서 다르다
근거: `docs/api/API_CONTRACT.md:192` (1~100자) vs `docs/board/drafts/DB_DRAFT.md:297` (2자 이상), `docs/DESIGN.md:425`
1자 `%a%`는 trigram 인덱스를 쓸 수 없어 매번 전체 스캔이다(시연에서 EXPLAIN을 보여주는 구간, `DEMO_SCENARIO.md:9`과 충돌).
수정 문구: 계약 `:192`를 `q: 공백 제거 후 2~100자(1자 이하는 400 VALIDATION_FAILED — trigram 인덱스를 쓸 수 없다)`로 통일하고 DESIGN 19장 검색 줄에 같은 조건을 적는다. 1자 허용을 유지한다면 `:192`에 `1자 키워드는 인덱스를 쓰지 못한다(전체 스캔 허용)`를 근거로 남긴다.

### M4 (사소) 고정 글 정렬과 인덱스·주석 불일치
근거: `docs/api/API_CONTRACT.md:193` (모든 정렬이 `isPinned DESC` 선행, 고정 글도 totals·페이지 크기 포함), `docs/board/V11__board.sql:65-72,80`
실제 `ORDER BY is_pinned DESC, …`인데 정렬 인덱스(66-72)에는 `is_pinned`가 없고, 80번 부분 인덱스는 선두 컬럼이 `category`라 고정 글 선행 정렬에 쓰이지 않는다. 데이터 규모(시드 15건~)에서는 문제가 아니므로 주석만 정확히.
수정 문구(V11 주석): `-- 목록 정렬용. 실제 ORDER BY는 is_pinned DESC가 선행하므로 이 인덱스는 선행 키 일부만 커버한다(고정 글 소수 전제, 수천 건 규모에서 재검토).`
또한 DESIGN 19장 "규칙" 표에 한 줄 추가: `목록 정렬 | 고정 글 우선 → 지정 정렬 → createdAt DESC → id DESC, 고정 글도 페이지 크기·총 건수에 포함 | 인덱스 ix_posts_* (부분)`

### M5 (사소) 상세 조회가 쓰기 트랜잭션이 된다
근거: `docs/api/API_CONTRACT.md:217`, `docs/tasks/TASK-005A.md:25`
조회수 집계 때문에 `GET` 상세가 쓰기 트랜잭션이 된다. 같은 글의 수정·좋아요와 `posts` 행 락을 다투고, 무토큰 조회까지 쓰기 경계에 들어오면 불필요하다.
수정 문구(TASK-005A.md:25에 추가): `무토큰·재조회는 읽기 전용 경로로 끝내고, post_views 삽입이 성공한 경우에만 같은 트랜잭션에서 view_count를 올린다. posts 행을 건드리는 UPDATE는 "한 문장, 즉시 커밋"으로 유지해 수정·좋아요와의 락 보유 시간을 겹치지 않게 한다.`

### M6 (사소) `serviceCode`/`areaCode` 문자 집합
근거: `docs/api/API_CONTRACT.md:191` (1~32자, 정확히 일치)
정확히 일치라 인젝션은 없지만, 패턴을 못 박으면 공백·와일드카드 문자가 그대로 들어와 로그만 더럽힌다.
수정 문구: `serviceCode`, `areaCode`는 `^[A-Za-z0-9_-]{1,32}$`, 위반은 400.

### M7 (사소) 토큰·신고 상세 로깅 금지
근거: `docs/api/API_CONTRACT.md:18` (토큰은 해시로만 저장), `:292` (신고자 비공개)
수정 문구(TASK-005A "할 것 5" 끝): `X-Guest-Token 원문, 신고자 식별자, 관리자 세션 ID는 로그·에러 응답·플래시 메시지에 남기지 않는다.`

## 3. 시연 문서 불일치 (계약 기준 2건, 나머지는 Gemini 리뷰)

1. `docs/board/DEMO_SCENARIO.md:12` — "B가 댓글 신고 → 관리자 처리 → B 새로고침 시 '표시할 수 없는 댓글입니다.'"는 계약(`API_CONTRACT.md:274`, `DESIGN.md:426`)상 **그 댓글에 공개 대댓글이 남아 있을 때만** 성립한다. 대댓글 없는 댓글을 숨기면 목록에서 그냥 사라진다. 수정 문구: `2:40 구간에서 대댓글을 단 루트 댓글을 신고 대상으로 지정한다("대댓글이 달린 루트 댓글"을 명시). 숨김 후 자리표시자 + commentCount 감소를 함께 보여준다.` (평가·시연 리뷰어와 동일 발견, 이 문서에 기재)
2. `docs/board/DEMO_SCENARIO.md:10,17` — 1:40~2:40 RECORD 구간은 005B 이후만 가능하다는 단서가 "준비 체크"에만 있다. 표 안에 `(005B 병합 후. 그 전에는 일반 글 작성 + 검증으로 대체)`를 바로 적고, `4:30` 구간의 "같은 게임으로 재작성"도 같은 조건임을 표기한다(`DEMO_SCENARIO.md:13`).

## 4. 확인했고 문제 없는 것 (근거)

- 스냅샷 위조 방지: 요청은 `gameId`만, 서버가 9필드 복사 + `ck_posts_record_snapshot` + `trg_posts_record_game_guard` 이중 방어 (`API_CONTRACT.md:241,252`, `V11__board.sql:50-56,203-224`).
- 게임당 RECORD 1건·soft delete 후 재작성·HIDDEN은 재작성 불가: 부분 유일 인덱스가 계약 문장과 정확히 일치 (`V11__board.sql:61-63` ↔ `API_CONTRACT.md:253`).
- 대댓글 1단계·부모 글 일치: 트리거가 `depth`를 서버 입력과 무관하게 강제하고 `FOR SHARE`로 부모를 잠근다 (`V11__board.sql:116-149`).
- 좋아요 멱등성과 토글 경로 제거: 재시도가 상태를 뒤집지 않는 설계 (`API_CONTRACT.md:284-285`).
- 조회수 중복 방지 키가 KST 일자로 고정 (`V11__board.sql:188-197`, `TASK-005A.md:25`) — 남은 약점은 I4(계정 무제한)뿐.
- 개인 상태 응답의 `Cache-Control: no-store` (`API_CONTRACT.md:208,234,257,274`).
- 관리자 계정이 게스트 토큰·`users.role`과 분리 (`API_CONTRACT.md:310`).
- 공개 경로에서 게임 존재 비노출: `/api/board/records/{gameId}` 204 (`API_CONTRACT.md:257`).
