# TASK-004F: 프론트 통합 (게임 2차 + 게시판) + 코드 가독성 + 리뷰 지적 수정

- 브랜치: `task/TASK-004F` (worktree `C:\Users\my\dev\sss-004f`)
- 담당: Codex
- 상태: 이 브랜치는 main(= TASK-004C 병합본)에서 만들었고, `origin/task/TASK-005C`(게시판 프론트)를 병합하는 중 **충돌 5개 파일이 남은 상태**로 넘겨준다.
  - `frontend/src/App.tsx`, `frontend/src/api/client.ts`, `frontend/src/api/mock/index.ts`, `frontend/src/api/types.ts`, `frontend/src/style.css`
- 선행: `AGENTS.md`, `docs/api/API_CONTRACT.md`(3장 v1.1, 4장 게시판), `docs/tasks/TASK-004C.md`, `docs/tasks/TASK-005C.md`, 두 쪽 브랜치의 커밋 이력(`git log main..origin/task/TASK-005C`, `git log dcd1d1d..main -- frontend`)
- `docs/reviews/REVIEW-1.md`는 `review/REVIEW-1` 브랜치에 있다: `git show review/REVIEW-1:docs/reviews/REVIEW-1.md` 의 6장

## 1. 충돌 해결 (가장 먼저, 기능 손실 0)
- 두 쪽 기능을 **모두** 살린다: 004C(전환점·반응·엔딩·매각·랭킹·금액 포맷·지도 수정), 005C(게시판 목록·상세·댓글·작성·내 글·홈 인기글·엔딩 → 인증글 진입점).
- 엔딩 화면이 양쪽에서 바뀌었다: 004C의 엔딩 요약(summary)과 005C의 "엔딩 인증하기/인증글 보기"를 한 화면에 합친다.
- 목 저장소: 004C는 v2 키로 분리했다. 게시판 목 저장소 키(`sss-mock-board-v1`)와 충돌 없게.
- 해결 직후 `npm run lint && npx vitest run && npm run build` 통과를 확인하고 **병합 커밋**을 먼저 만든다(이후 리팩터링과 분리).

## 2. 가독성 (동작 변경 없음)
현재 App.tsx 등은 컴포넌트 전체가 한 줄에 몰려 있어 읽을 수 없다(포트폴리오·과제 평가 대상).
- Prettier 도입(`.prettierrc`: printWidth 100, singleQuote, semi). `package.json`에 `format`, `format:check` 스크립트. 의존성 추가는 메인에게 "npm 설치 요청".
- 전체 `frontend/src` 포맷. 그다음 `App.tsx`를 페이지 단위 파일로 분리(`pages/Home.tsx`, `pages/NewOwner.tsx`, `pages/ShopSetup.tsx`, `pages/GamePage.tsx` …), 목 `mock/index.ts`의 거대한 if-else를 경로별 핸들러 함수/테이블로.
- 커밋을 "포맷만" 과 "파일 분리" 로 나눠서 리뷰하기 쉽게.

## 3. 리뷰 지적 수정 (REVIEW-1 6장)
- 6-2: `Report.floatingPopulation`, `openingRatePct`, `closingRatePct`는 `number | null`. null이면 "자료 없음". 실제 백엔드가 null을 줄 수 있음
- 6-3: 401이면 저장된 게스트 토큰을 지우고 1회 재발급 후 재시도(게시판 쪽 기존 처리와 하나의 방식으로 통일)
- 6-4: 엔딩 문구는 endingType별(CLOSED 폐업, SOLD 매각, LONG_RUNNING 장수) — 이미 있으면 테스트만
- 각각 테스트

## 완료 조건
- `npm run format:check && npm run lint && npx vitest run && npm run build` 통과 (테스트 수 보고)
- 004C 테스트 19개 + 005C 테스트 37개가 모두 살아 있음(중복 제거로 줄었다면 이유)
- 커밋: 충돌 해결 → 포맷 → 분리 → 리뷰 수정. push 금지
