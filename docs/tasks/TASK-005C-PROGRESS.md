# TASK-005C 진행 기록

## 1단계 (2026-11-18)
- Vite `/api` 개발 프록시를 `VITE_API_TARGET`으로 변경(기본 `http://localhost:8081`). 병렬 백엔드에 붙을 때는 프론트 실행 셸에서 `VITE_USE_MOCK=false`, `VITE_API_TARGET=http://localhost:8085` 설정.
- 계약 4장의 사용자 요청·응답과 오류 코드 타입, `frontend/src/api/board.ts` 어댑터 추가. 목록·공개 링크 `none`, 상세·댓글 조회 `optional`(저장 토큰만), 사용자 쓰기·내 글 `required`(기존 게스트 발급). `204`는 `undefined`, 오류는 `ApiError.status/code/message`. 게임 `request()` 네트워크 동작은 그대로이며 `/board/**` 직진입에서만 앱 초기 자동 게스트 발급을 생략.
- 목: 가상 상호의 RECORD 3건을 포함한 게시글 29건, 목록·상세·인증 링크·글 수정/삭제·좋아요·글 신고 응답. 글 삭제/좋아요/인증 링크 없음은 204, 버전 충돌/중복 신고는 서로 다른 409 코드. `sss-mock-board-v1` 로컬 저장소 사용.
- `/board`: 카테고리, 정렬, 검색 종류/검색어, RECORD에만 표시되는 업종·상권 코드 필터, 고정 표시, 페이징, 빈 목록. 쿼리스트링으로 공유/뒤로 가기 가능. 상세 경로와 다른 진입점은 아직 미구현.
- 막힌 것: 1단계 구현에 차단 없음. 진짜 백엔드 E2E는 005A 병렬 작업 중이라 미검증. 실제 통신 대신 `fetch` 응답과 목 응답 각각에서 어댑터 검증.

## 검증
- `frontend`에서 `Set-Location`에 더해 `[Environment]::CurrentDirectory`를 맞춘 뒤 `$env:npm_config_script_shell=(Get-Command pwsh).Source`, `$env:NODE_OPTIONS='--no-experimental-webstorage'` 설정. 후자는 Node v26 내장 `localStorage`와 jsdom 충돌 방지용이며 앱 변경 없음.
- `npm run lint`: 통과. `npm test -- --run`: 3 파일, 13 테스트 통과(기존 게임 4, 게시판 어댑터 6, 목록 3). `npm run build`: TypeScript와 Vite 프로덕션 빌드 통과.

## 다음 세션
- 2단계: 상세·댓글·좋아요·신고 화면 및 테스트. 목의 댓글(현재 목록 모양만 제공)·대댓글 데이터/변경·댓글 신고와 카운터 동기화도 이 단계에 연결.
- 3단계: 작성·수정·내 글·진입점·캡처. 목의 신규 글/RECORD 작성과 내 글 숨김 사례를 연결. RECORD 정식 작성 URL은 `/board/write?category=RECORD&gameId=…`; `category` 없이 `gameId`만 있어도 같은 RECORD 작성 화면으로 해석(메인과 합의, 계약 예시는 메인이 정리).
