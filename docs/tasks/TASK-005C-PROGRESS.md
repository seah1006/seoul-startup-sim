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

## 2단계 시작 (2026-11-18)
- 게시글 상세·댓글·신고 및 목/테스트를 구현한다. 3단계 URL 결정은 위 인계를 따른다.

## 2단계 완료 (2026-11-18)
- `/board/posts/:postId`: 일반 텍스트와 줄바꿈, RECORD 요약 카드(기존 금액 포맷)와 서버 공유 링크, 직렬화한 낙관적 좋아요/실패 롤백, 본인 수정 링크·버전 포함 삭제/목록 복귀, 게시글·댓글 공통 사유 선택 신고 모달을 구현했다.
- 루트/대댓글 독립 페이징, 댓글·대댓글 등록/수정/삭제 후 재조회, 대댓글이 남은 삭제 루트의 자리표시자, 대댓글의 답글 쓰기 숨김을 구현했다. 409는 최신 댓글/게시글 재조회 안내, 429는 잠시 후 재시도 안내, 쓰기 401은 사용자 선택으로 게스트 세션을 다시 시작한다.
- 목 댓글 CRUD/신고/요청 제한 및 ACTIVE 카운터를 추가했다. 기존 `sss-mock-board-v1` 데이터는 댓글·제한 필드가 없는 경우에 기본값을 쓰므로 키를 바꾸지 않았다. 선택 인증 조회의 401은 저장된 토큰을 변경하지 않고 무헤더로 한 번 재시도한다. 범위 밖 게시판 페이지의 첫 페이지 복귀를 추가했다.
- 검증: `frontend`의 프로세스 작업 경로를 맞추고 `$env:npm_config_script_shell=(Get-Command pwsh).Source`, `$env:NODE_OPTIONS='--no-experimental-webstorage'` 설정 후 `npm run lint`, `npm test -- --run`(4파일, 26개), `npm run build` 통과. Node v26/jsdom 충돌 회피는 실행 환경만 변경했다.
- 3단계에 남음: 작성·수정 화면과 내 글, 홈/엔딩/메뉴 진입점, 신규 글/RECORD 작성 목, 목 화면 캡처. RECORD 작성 URL은 `/board/write?category=RECORD&gameId=…`; `?gameId=…`만 있어도 RECORD로 해석한다. 상세의 수정 링크는 3단계 라우트 설치 전에는 편집 화면으로 연결되지 않는다.
- 실제 백엔드/Thymeleaf E2E와 브라우저 캡처는 미검증. 상세의 `/share` 링크는 개발 프록시로 설정한 백엔드에 전달하며, 목 모드는 공유 페이지 자체를 합성하지 않는다. 백엔드나 API 계약 문서는 수정하지 않았다.
