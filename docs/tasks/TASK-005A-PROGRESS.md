# TASK-005A 진행 기록

## 1단계: 게시글 코어
- 완료: V11 원본 그대로 복사(SHA-256 동일), BoardGuestAuth와 범위 제한 BoardExceptionHandler, 페이지/게시글 DTO 구현.
- 완료: 게시글 목록의 페이징·고정 우선·정렬/검색 각 4종·LIKE 이스케이프·카테고리/업종/상권 필터, 선택 인증 상세 및 KST 일별 조회수, 작성(RECORD 400), 수정·삭제(버전 조건부 SQL과 0행 재판정), 내 글(ACTIVE/HIDDEN). 상세·내 글은 `no-store`.
- 완료: 통합 테스트 7개 추가. 관리자 숨김과 사용자 수정이 경합할 때 조건부 UPDATE 0행 → 404를 실제 PostgreSQL 행 잠금으로 검증.
- 계약 해석(메인과 합의): 인증 → 형식 검증(`gameId` 명시 포함) → 공개 대상 → 소유권 → RECORD 전환 금지(상태 의존 400) → 버전. 기존 RECORD는 첨부 고정 상태로 제목·본문 수정 가능.
- 막힌 것: 기능 구현은 없음. 최종 전체 `check` 결과는 아래 참조.
- 커밋: `5235938` 진행 기록 시작, `aa5724e` V11, `9b53211` API, `8a1ee1d` 통합 테스트, `d1de5f5` JSON 타입 엄격 검증.

## 검증
- `backend\gradlew.bat --no-daemon compileJava --offline`: 성공.
- `backend\gradlew.bat --no-daemon test --offline --tests '*BoardPostIntegrationTest'`: 통과(7 tests, 0 failures, 0 errors, 0 skipped; JSON 타입 보강 전).
- `backend\gradlew.bat --no-daemon check --offline`: JSON 타입 보강 포함 실행 시작, 컴파일/테스트 컴파일 성공. 전체 결과 확인 필요.
- `git grep --untracked -n 'game\.' -- backend/src/main/java/com/seah/startupsim/board`: `BoardGuestAuth`의 두 import만 출력. frontend/game 소스 미수정, push 없음. 메인 DB 미사용.

## 다음 단계
- 2단계: 댓글·대댓글·좋아요와 공개 카운터 트랜잭션 및 동시성 테스트. `BoardExceptionHandler`의 `assignableTypes`에 새 REST 컨트롤러 추가.
- 3단계: 신고·관리자 Thymeleaf. 4단계: 레이트 리밋·시드 및 완료 검증(`clean check`, 전용 DB의 EXPLAIN, curl 데모).

## 2단계: 댓글·대댓글·좋아요
- 2단계 시작. 메인 확인: 1단계 전체 check 통과(52 tests, 0 failures).
- 완료: 루트와 답글 분리 페이징 및 선택 인증(`no-store`), 댓글 작성·수정·삭제, 부모 검증(다른 글·비공개는 404, 공개 대댓글은 400), `COMMENT_VERSION_CONFLICT` 및 자리표시자.
- 완료: 좋아요 PUT/DELETE 멱등 처리, 실제 삽입·삭제 행 수 기준 카운터. 댓글·좋아요 쓰기의 공통 잠금 순서는 글(`FOR NO KEY UPDATE`) 먼저이며 대댓글 작성 시 부모도 잠근다. `comment_count`는 ACTIVE 합계, `reply_count`는 ACTIVE 답글 수. 모든 감소에 `count > 0` 가드. 카운터는 버전·수정일 불변.
- 1단계 리뷰 반영: 예외 advice 범위에 새 컨트롤러 추가, 게스트 인증 바인딩 공용화, 부모를 DB 쓰기 전 검증, 미분류 DB 제약·트리거 오류는 SQLSTATE만 기록. 비공개 게시글의 타 작성자 404·형식 검증 우선·조회수 불변도 테스트.
- 검증: `gradlew.bat --no-daemon test --offline --tests '*BoardInteractionIntegrationTest'` 통과(5 tests, 0 failures, 0 errors, 0 skipped). 실제 HTTP 동시 대댓글 2건 모두 201(답글 수 2), 좋아요 동일 사용자 10회 → 1, 서로 다른 10명 → 10, 중복 해제 → 0. 비공개 404/카운터 불변, 댓글 409, 페이징/자리표시자 확인.
- 커밋: `a1e0be2` 시작, `d29f0e6` 댓글, `b973530` 좋아요, `c509582` HTTP 통합 테스트.
- 다음: 3단계 신고·관리자에서는 댓글 상태 변경도 글을 먼저 잠그고, 상태 변경과 공개 카운터 재계산을 동일 트랜잭션에서 수행. 4단계 레이트 리밋·시드 및 완료 검증은 미착수.
- 최종 검증: `gradlew.bat --no-daemon check --offline` 성공(2m 13s; 57 tests, 0 failures, 0 errors, 0 skipped). frontend/game 및 V11 변경 없음, push 없음.

## 3단계: 신고·관리자
- 게시글·댓글 신고: 인증/형식/공개성/자기 신고/중복 순서, 등록 201. PostgreSQL `ux_post_reports_target_reporter`만 409로 변환하며 오류에 트리거 원문을 노출하지 않는다.
- 관리자 HTML: 목록·상세·필터, 신고 일괄 처리/기각, 게시글·댓글 숨김/복구/삭제, 게시글 고정. 글→댓글→신고(id 순) 잠금, 상태 전이·카운터 재계산 원자적 처리, HTML 400/409, 성공 303·플래시.
- Testcontainers: `gradlew.bat --no-daemon test --offline --tests '*BoardInteractionIntegrationTest'` 재검증 5 tests, `--tests '*BoardModerationIntegrationTest'` 2 tests. 모두 실패·오류·스킵 0. 관리자 CSRF와 텍스트 이스케이프 포함.
- 커밋: `38d5f5d` 신고/관리자, `d7f7fdb` 통합 테스트. 2단계 테스트는 기커밋(`c509582`, 줄바꿈 `6e6e4de`)으로 중복 커밋하지 않음.
- 다음: 4단계 레이트 리밋·시드, 전체 검증·전용 DB EXPLAIN·curl 시연.
## 4단계: 레이트 리밋·데모 시드 및 완료 검증
- `d9b0789`: 사용자별 고정 창으로 글 3·댓글 10·신고 5회/분, 초과 429 `RATE_LIMITED`. 글 4번째 429 및 다른 사용자 분리 통합 테스트 포함.
- `15c318f`: Flyway 밖의 수동 데모 시드. 5437 전용 Compose 프로젝트 `sss-task005a`에서 `docker compose -p sss-task005a exec -T db`의 psql로 `seed_board_demo.sql` 적재 성공(BEGIN/DO/COMMIT). FREE 5, QNA 5, TIP 5, RECORD 3, 대댓글 있는 신고 대상 및 PENDING 2건.
- `gradlew.bat --no-daemon clean check --offline`: 성공(2m 47s), XML: tests=60, failures=0, errors=0, skipped=0. 네트워크 준비용 온라인 `clean check`는 이번 세션 미실행(의존성·이미지는 캐시 상태).
- 전용 DB `SELECT name FROM pg_available_extensions WHERE name='pg_trgm'`: `pg_trgm` 1행. 제목 검색 계획 실측:
  ```text
  Limit  (cost=2.28..2.28 rows=1 width=17)
    ->  Sort  (cost=2.28..2.28 rows=1 width=17)
          Sort Key: is_pinned DESC, created_at DESC, id DESC
          ->  Seq Scan on posts  (cost=0.00..2.27 rows=1 width=17)
                Filter: ((title ~~* '%체크리스트%'::text) AND (status = 'ACTIVE'::text))
  ```
  `SET LOCAL enable_seqscan=off`의 제목 조건 단독 계획:
  ```text
  Bitmap Heap Scan on posts  (cost=25.91..29.92 rows=1 width=8)
    Recheck Cond: (title ~~* '%체크리스트%'::text)
    ->  Bitmap Index Scan on ix_posts_title_trgm  (cost=0.00..25.91 rows=1 width=0)
          Index Cond: (title ~~* '%체크리스트%'::text)
  ```
- 8085 `curl.exe -i`: 목록 `HTTP/1.1 200`(18건, page size 10), 제목 `searchType=title&q=체크리스트` 검색 `HTTP/1.1 200`(1건), 공지 상세 `HTTP/1.1 200`, 글 작성 `HTTP/1.1 201`, 같은 버전 재수정 `HTTP/1.1 409 POST_VERSION_CONFLICT`, 좋아요 `HTTP/1.1 204`. 토큰 미기록.
- `git grep --untracked -n 'game\.' -- backend/src/main/java/com/seah/startupsim/board`: BoardGuestAuth import 2줄뿐. 5433 미접속. V11 원본과 기존 마이그레이션/순서 설정 유지. push/merge 없음. RECORD 작성 및 연결 조회는 TASK-005B, 프록시 수정은 TASK-005C 범위로 여기서는 미구현.

## 리뷰 반영
- [3-2] 상세 재조회에서 글 사전 배타잠금 제거. `post_views` 신규 INSERT 성공 시에만 `UPDATE posts ... WHERE status='ACTIVE'`로 조회수 증가. 실패 시 트랜잭션 롤백하여 비공개 글의 조회 기록/카운터 모두 유지. 재조회 두 건의 행 잠금 비대기 및 관리자 숨김 경합 회귀 테스트 추가.
- [3-3] 관리자 게시글·신고·댓글 목록 `LIMIT 21 OFFSET page*20`, 표시 20건 및 hasNext. 이전/다음 링크에서 필터 유지. 신고 상세의 댓글은 전체 댓글 목록 대신 ID 단건 조회로 변경.
- [3-4] 관리자 게시글 상태 변경 시 고정 해제. 숨김→복구와 고정→삭제 회귀 테스트 추가.
- [2-1 게시판 범위] 사용자별 슬라이딩 60초 제한 + IP별 보조 제한(기본 글 10/댓글 30/신고 15). IP 설정과 프록시 신뢰 opt-in은 API 계약 4장 참조. 모든 admission에서 만료 식별자 정리. 경계 버스트·사용자 교체 IP 우회·프록시 opt-in·만료 정리 단위 테스트 추가. `/api/guest` 및 game/common은 미수정.
- 범위: board 소스/테스트, 관리자 board 템플릿 및 문서만 수정. 마이그레이션, 메인 DB, push 미실행. DB 통합 검증은 격리 Testcontainers만 사용.
- 최종 검증: `gradlew.bat --offline test --tests '*Board*' --no-daemon` 성공(22 tests), `gradlew.bat --offline clean check --no-daemon` 성공(2m 5s, 67 tests / failures 0 / errors 0 / skipped 0). `git diff --check` 통과.
