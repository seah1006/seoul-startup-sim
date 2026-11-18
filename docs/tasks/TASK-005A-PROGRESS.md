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
