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
