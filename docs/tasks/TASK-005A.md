# TASK-005A: 사장님 커뮤니티 게시판 코어 (백엔드)

- 브랜치: `task/TASK-005A` (worktree `C:\Users\my\dev\sss-005a`, **전용 DB 5437**, 앱 8085)
- 담당: Codex
- 선행: `AGENTS.md`, `docs/DESIGN.md` 19장, `docs/api/API_CONTRACT.md` 공통 + **4장 v1.2(특히 "판정 순서와 안전 규칙")**, `docs/board/V11__board.sql`, `docs/board/drafts/DB_DRAFT.md`(근거), `docs/board/drafts/REVIEW_*.md`(교차 리뷰)
- **Flyway: `V11__board.sql` 만 사용.** `docs/board/V11__board.sql`을 그대로 `backend/src/main/resources/db/migration/V11__board.sql`로 복사한다(실제 postgis/postgis:16-3.5에서 V1~V7 위 적용, 규칙·동시성 테스트 통과본). 바꿔야 하면 바꾸지 말고 "제안"으로 보고.
- **Flyway 순서 주의 (차단 리뷰 C2)**: V8·V9(게임 작업), V10(TASK-004D)이 아직 없다. V11을 먼저 적용한 DB에는 기본 설정(`outOfOrder=false`)으로 V8~V10이 나중에 적용되지 않는다. 그래서
  - 이 작업은 **전용 DB(5437)에서만** 적용한다. 메인 DB(5433)에 V11을 적용하지 않는다.
  - main 병합은 메인 에이전트가 V8~V10 병합 뒤에 한다. 전용 DB는 버리고 다시 만들 수 있는 것으로 취급한다.
  - Flyway `out-of-order` 전역 설정은 켜지 않는다.
- **game 패키지 수정 금지** (TASK-004A). 게시판에서 game 패키지 의존은 `BoardGuestAuth` 한 클래스의 `GuestAuth`, `GameException` import 두 개만 허용.
- 이 작업에서 **RECORD 작성은 열지 않는다**(TASK-005B). `category: RECORD` 작성 요청은 400 `VALIDATION_FAILED`, 메시지 "엔딩 인증글은 아직 작성할 수 없습니다." `/api/board/records/{gameId}`도 005B에서 만든다. 목록·상세에서 RECORD 글을 읽는 코드, RECORD 필터, `record` 응답 필드는 만든다.

## 왜
기말 평가에서 게시판 CRUD를 커뮤니티 과제 수준으로 보여줘야 한다. 게임 쪽 남은 작업(전환점, 엔딩·랭킹)에 의존하지 않는 부분을 먼저 끝내 둔다.

## 할 것
1. **패키지** `com.seah.startupsim.board` (계약 4장 "Spring 매핑"의 클래스 이름)
   - `@RestController`: `BoardPostController`, `BoardCommentController`, `BoardReportController`
   - `@Controller`: `AdminBoardController` (`/admin/board/**`)
   - Service: `BoardPostService`, `BoardCommentService`, `BoardLikeService`, `BoardReportService`, `BoardModerationService`
   - Repository: **JdbcTemplate으로 통일**(부분 인덱스, `ON CONFLICT`, 행 수 기반 카운터가 네이티브 SQL 중심. 새 의존성 추가 금지). SQL은 텍스트 블록, 컬럼 명시. 목록 동적 검색은 enum 화이트리스트로만 조립.
   - DTO record: 요청 `CreatePostRequest`, `UpdatePostRequest`, `CreateCommentRequest`, `UpdateCommentRequest`, `CreateReportRequest`, `PostSearchQuery` / 응답 `PageResponse<T>`, `PostListItem`, `PostDetail`, `MyPostListItem`, `RecordSnapshot`, `CommentItem`, `ReportCreated`. `version`은 `Long @NotNull @PositiveOrZero`(누락과 0 구분). 페이지 입력은 직접 검증한 뒤 `PageRequest`로 변환.
2. **인증 어댑터 (차단 리뷰 C1)**: `BoardGuestAuth`가 `GuestAuth.resolve()`를 호출하고 `GameException`만 잡아 게시판 401 `UNAUTHORIZED`(고정 메시지)로 바꾼다(`GameException` 필드 접근 금지). 헤더는 필수·선택 모두 `@RequestHeader(name="X-Guest-Token", required=false)`로 받고, 필수 경로는 누락도 401. 선택 경로(상세, 댓글 목록)는 헤더가 없을 때만 익명, 있는데 무효면 401.
3. **예외 처리**: `BoardException`(상태, 코드, 메시지) + `BoardExceptionHandler` = `@RestControllerAdvice(assignableTypes = {게시판 REST 컨트롤러})`, `@Order(Ordered.HIGHEST_PRECEDENCE)`, `BoardException`만 처리해 공통 `ApiError` 형식으로 응답. 바인딩·검증 오류는 기존 `ApiExceptionHandler`에 맡긴다. 기존 핸들러 동작은 바꾸지 않는다.
4. **판정 순서와 DB 오류 변환 (리뷰 I1)**: 서비스에서 계약 4장 순서(401 → 400 → 404 → 403 → 409)로 **먼저 검증**한다. DB 예외는 `DataIntegrityViolationException`의 원인 PostgreSQL 예외에서 **제약 이름**을 확인해 `ux_post_reports_target_reporter` → 409 `ALREADY_REPORTED`만 변환한다(005B에서 `ux_posts_record_game` 추가). 23514를 일괄 400/409로 바꾸지 않는다. 트리거 원문 메시지는 응답·로그에 내보내지 않는다. 미분류 위반은 500으로 남겨 조사.
5. **API** (계약 4장 그대로): 목록·상세·작성·수정·삭제, 내 글, 댓글(루트/대댓글 분리 페이징)·작성·수정·삭제, 좋아요 PUT/DELETE, 신고.
6. **트랜잭션·동시성** (`@Transactional`은 서비스에)
   - 좋아요: 대상 공개 확인 → `INSERT … ON CONFLICT (post_id, user_id) DO NOTHING` / `DELETE`의 실제 행 수가 1일 때만 `like_count ± 1`. 비공개 대상 404, 아무것도 안 바뀜.
   - 댓글: 대댓글은 부모를 먼저 확인(없음·다른 글·비공개 404, 부모가 대댓글이면 400). 작성·삭제 시 `comment_count`(ACTIVE 루트+대댓글)와 부모 `reply_count`를 같은 트랜잭션에서 갱신. 감소는 `WHERE count > 0`.
   - 조회수: 유효 토큰일 때만 `post_views` 삽입 성공 시 `view_count + 1` (KST, `Asia/Seoul`).
   - 카운터·조회수 UPDATE는 `version`, `updated_at`을 건드리지 않는다.
   - 수정·삭제: `UPDATE … WHERE id=? AND version=? AND status='ACTIVE'`가 0행이면 다시 읽어 404/403/409 재판정.
7. **남용 방지**: 사용자당 분당 글 3, 댓글 10, 신고 5(메모리 고정 창, 단일 인스턴스 전제). 초과 시 429 `RATE_LIMITED`. 설정값은 `application.yml`.
8. **관리자** (기존 `/admin/**` 체인, CSRF, 인메모리 계정)
   - 신고 목록·상세, 처리(RESOLVED → 대상 HIDDEN, 같은 대상 PENDING 일괄 종결)·기각, 글/댓글 숨김·복구·삭제, 고정. 상태 전이표와 잠금 순서는 계약 4장 규칙 그대로. 댓글 숨김·복구·삭제 시 카운터 재계산.
   - 뷰는 `templates/admin/board/`에 두고 `admin/fragments :: head/nav` 재사용, 공통 nav에 "게시판" 링크 추가. 폼은 `th:action`(CSRF hidden 필드 렌더 확인).
   - `@Valid @ModelAttribute` 바로 뒤 `BindingResult`. 입력 오류 400, 상태 충돌 409로 **HTML 폼 재표시**(관리자 컨트롤러에서 예외를 던져 JSON advice로 새지 않게). 성공은 `RedirectView` + `HttpStatus.SEE_OTHER`(303) + `RedirectAttributes.addFlashAttribute("message", …)`.
   - `handled_by`는 `Principal.getName()`. 데모 로그인은 `.env`의 `ADMIN_USERNAME`, `ADMIN_PASSWORD`.
9. **출력 안전**: Thymeleaf는 `th:text`/`th:content`만(`th:utext` 금지). 토큰·신고자 정보 로그 금지.
10. **프론트 개발 연결 (리뷰 I3)**: CORS를 열지 않는다(동일 출처). `frontend/vite.config.ts`의 프록시 target을 환경변수(예: `VITE_API_TARGET`, 기본 `http://localhost:8081`)로 바꿔 8085에서도 쓸 수 있게 한다. 이 한 줄 외 frontend 수정 금지.
11. **데모 시드** (Flyway 아님): `docs/board/seed_board_demo.sql`
   - 가상 사용자 3명, 글 15건 이상(공지 고정 1, TIP/QNA/FREE 각 3 이상, 2쪽 이상), 댓글·대댓글(**신고 시연용: 대댓글이 달린 루트 댓글 1개 이상**), 좋아요·조회수 분포, PENDING 신고 2건.
   - **RECORD 미리보기용**: 시드 사용자 소유 ENDED 게임 3개(엔딩 3종)와 RECORD 글 3건을 SQL로 직접 넣는다(트리거 통과 조건 충족). 005B 전에도 RECORD 탭 필터와 상세 카드 시연이 가능하게.
   - 가게·사장님 이름은 전부 가상, 상권명은 실제 지명 가능. **실제 상호 금지**(AGENTS.md 6).

## 테스트 (Testcontainers PostGIS, 기존 `GameIntegrationTest`·`AdminWebIntegrationTest` 방식)
- 목록: 페이징 메타, 정렬 4종, 검색 4종(`\`·`%`·`_` 리터럴), 카테고리 필터, `serviceCode`/`areaCode` 필터(시드 RECORD), RECORD가 아닌 category와 코드 필터 동시 지정 400, 고정 글 우선, 범위 밖 페이지 빈 목록
- 작성: 400(빈 제목, 길이 초과, 잘못된 enum, RECORD 차단), 401(토큰 없음, 무효 토큰), `201` + `Location` 헤더
- 선택 인증: 상세·댓글 목록에 무효 토큰 → 401, 무헤더 → 200
- 권한·순서: 타인 수정·삭제 403, 관리자가 숨긴 글을 작성자가 수정 → 404(409 아님), HIDDEN·DELETED 상세 404
- 낙관적 락: 같은 version으로 두 번 수정 → 두 번째 409 `POST_VERSION_CONFLICT`, 댓글도 409 `COMMENT_VERSION_CONFLICT`
- 삭제: 글 삭제 후 목록·상세에서 사라짐, 재삭제 404
- 좋아요 동시성 (리뷰 I6): fixture를 먼저 커밋하고 테스트 메서드에 `@Transactional` 없이, 실제 HTTP로 worker마다 자기 트랜잭션. 글 A: 같은 사용자 PUT 10회 병렬 → 행 1, `like_count` 1. 글 B: 서로 다른 사용자 10명 → 10. 비공개 글 좋아요 404 + 카운터 불변. 좋아요 후 `version`·`updated_at` 불변
- 대댓글: 대댓글의 대댓글 400, 다른 글 부모 404, **같은 루트에 동시 대댓글 2건 → 둘 다 201, `reply_count` 2**(V11 `FOR KEY SHARE` 교착 회귀 방지)
- 댓글 삭제·숨김: 자리표시자 규칙(ACTIVE 답글 있을 때만), `comment_count`·`reply_count` 재계산
- 조회수: 무토큰 0, 같은 사용자 2회 → 1
- 신고: 비공개 대상 404, 자기 글 403, 중복 409, 관리자 처리 후 대상 HIDDEN + 같은 대상 PENDING 일괄 RESOLVED
- 관리자: 미로그인 리다이렉트, CSRF 없는 POST 403, 성공 303 + 플래시, `BindingResult` 오류 400 폼 재표시, 고정(숨김 글 고정 불가), 복구(`hidden_at` 초기화)
- 레이트 리밋: 분당 글 4번째 429
- 오류 응답 형식: 대표 4xx가 `{code,message,timestamp}`

## 완료 조건 (리뷰 I7)
1. 사전 준비: 네트워크가 되는 환경에서 `Push-Location backend; .\gradlew.bat clean check; Pop-Location`를 한 번 실행해 의존성과 Testcontainers 이미지를 받아 둔다. 그다음 `Push-Location backend; .\gradlew.bat clean check --offline; Pop-Location` 통과. 테스트 결과 XML의 tests·failures·skipped 합계 보고
2. 전용 DB에서 `docker compose exec -T db psql -U <DB_USERNAME> -d seoul_startup_sim -c "SELECT name FROM pg_available_extensions WHERE name='pg_trgm'"` 결과. 검색 SQL `EXPLAIN`은 실제 계획을 그대로 보고(소량 시드는 순차 탐색 허용), 인덱스 사용 가능성은 `SET LOCAL enable_seqscan=off`로 따로 확인
3. `git grep --untracked -n "game\." -- backend/src/main/java/com/seah/startupsim/board` 결과가 `BoardGuestAuth`의 import 두 줄뿐
4. 시드 적재 후 `curl.exe -i` 시연 6개(목록, 검색, 상세, 작성, 수정 409, 좋아요 204)의 상태줄과 응답 요약. 토큰 값은 보고에서 가린다
