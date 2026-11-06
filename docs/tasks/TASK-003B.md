# TASK-003B: React 프론트엔드 1차 (홈, 사장님 만들기, 지도에서 상권 고르기, 결산 카드)

- 브랜치: `task/TASK-003B` (worktree `C:\Users\my\dev\sss-003b`)
- 담당: Codex
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 2~4장·14장, **`docs/api/API_CONTRACT.md` 전체**

## 목표
`frontend/`에 게임 화면 1차를 만든다. 백엔드 게임 API(003A)는 동시에 개발 중이므로 **계약 기반 목(mock)**으로 먼저 완성하고, 실제 API로 쉽게 바꿀 수 있게 한다.

## 기술
- Vite + React + TypeScript, React Router, 상태는 가볍게(React Query 또는 직접 fetch 훅 중 택1, 이유 보고)
- 스타일: CSS Modules 또는 순수 CSS. UI 라이브러리 없이 깔끔하게(가독성 좋은 큰 숫자, 카드형). 모바일 폭(390px)과 데스크톱 모두
- 카카오맵 JavaScript SDK (`VITE_KAKAO_JS_KEY`, `frontend/.env.local` — gitignore 확인. 메인 저장소 루트 .env에는 이 키가 없으니 `.env.local.example`만 만들고, 실행 확인은 키 없이 지도 대신 안내 문구가 나오게 처리)
- 개발 서버 5173, `/api`는 Vite proxy로 `http://localhost:8081`
- API 레이어: `src/api/` 에 계약 타입(`types.ts`)과 클라이언트. `VITE_USE_MOCK=true`면 `src/api/mock/`의 목 구현(계약 예시 JSON 기반, 결정론적)으로 동작

## 화면
1. **홈**: 게임 소개 한 줄, "새 창업 시작" 버튼, (자리만) 오늘의 랭킹, 데이터 출처 표기
2. **사장님 만들기**: 이름 → 배경 4종 → 후보 3명(능력치 6종 막대, 합계 240 표시, "다른 후보 보기"=seed 변경) → 집중 능력치 최대 2개 → 확인
3. **상권 고르기**: 카카오맵 + 상권 폴리곤(실제 `GET /api/areas?bbox`는 이미 구현돼 있으니 **이건 목 대신 실제 API 사용 가능**), 상권 검색, 상권 클릭 → 상권 리포트 패널(목), 업종 선택(외식 10개), 지도에서 가게 위치 찍기 → 반경 300m 경쟁 점포 마커(목) → 창업
4. **분기 운영**: 결정 폼(가격 수준, 영업시간, 직원, 마케팅, 사장님 활동) → "다음 분기"
5. **분기 결산 카드**: 계약의 `QuarterResult`를 DESIGN 4장 모양으로. 큰 숫자 headline → 매출/방문객/객단가 → 상권 내 순위 → 능력치 변화 → 상권 소식(실제 데이터 출처 표기) → dataNotes
6. **기록**: 분기별 순이익 간단 차트(라이브러리 없이 SVG 막대 정도)
- 게스트 토큰: 첫 진입 시 `POST /api/guest`, localStorage 저장, 모든 요청 헤더
- advance 요청은 `Idempotency-Key`(crypto.randomUUID)와 expectedVersion 포함, 409면 최신 상태 다시 불러오기

## 테스트
- Vitest + Testing Library: 결산 카드 렌더링(금액 포맷 "1,240만 원"), 사장님 후보 합계, advance 409 처리
- `npm run build`, `npm run lint`(ESLint) 통과

## 하지 않을 것
백엔드 코드 수정, 전환점/반응/랭킹 실제 구현(자리 표시만), 카카오 로그인

## 완료 조건
1. `npm ci && npm run lint && npm run test -- --run && npm run build` 통과 (인터넷이 막혀 npm 설치가 안 되면 package.json을 확정해 저장하고 메인에게 "npm 설치 요청" — 메인이 대신 설치)
2. `VITE_USE_MOCK=true npm run dev` 로 띄워 주요 화면을 브라우저로 확인할 수 있게. 화면 캡처는 메인이 함
3. `frontend/README.md`에 실행 방법
