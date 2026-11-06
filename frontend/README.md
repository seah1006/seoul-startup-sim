# 서울 사장님 프론트엔드

Node 24, Vite + React + TypeScript. React Router를 사용합니다.

## 실행 (PowerShell)
```powershell
npm ci
$env:VITE_USE_MOCK='true'
npm run dev
```
http://localhost:5173 에서 홈 → 사장님 생성 → 상권 검색 → 업종·위치·가게 이름 → 창업 → 분기 운영 → 결산 → 기록을 확인합니다. 게스트와 목 게임은 localStorage에 저장되어 새로고침 후 이어갈 수 있습니다.

`.env.local.example`을 `.env.local`로 복사하고 필요 시 `VITE_KAKAO_JS_KEY`를 입력합니다. 키를 커밋하거나 로그로 출력하지 마세요. 카카오 플랫폼에 `http://localhost:5173` 등록이 필요합니다. 키가 없거나 SDK 연결에 실패해도 상권 검색과 중심점 선택으로 플레이할 수 있습니다.

기본은 목 모드입니다. `VITE_USE_MOCK=false` 설정 시 모든 계약 API를 실제 백엔드로 요청합니다. `/api`는 localhost:8081로 프록시합니다. 실제 API가 실패한 경우 조용히 목으로 대체하지 않고 오류를 표시합니다. 목 상권은 연습용 사각형으로, 실제 행정·상권 경계가 아닙니다.

## 검증
```powershell
npm run lint
npm run test -- --run
npm run build
```

## 구현 선택
직접 fetch + 작은 컴포넌트 훅을 선택했습니다. 화면 수와 API 수가 작아 별도 캐시 의존성 없이 요청·오류·로딩을 명시적으로 관리합니다. 게임 상태는 서버(목 모드에서는 결정론적 localStorage 구현)가 소유합니다. 게스트 발급은 최초 1회, 이후 모든 요청에 X-Guest-Token을 붙입니다. advance는 UUID 키와 expectedVersion을 전달하고 409에서 최신 게임을 조회합니다.

목 결과·상권 소식·경쟁점은 목임을 표시합니다. 실제 매출, 랭킹, 상호로 오인할 연출은 하지 않습니다. 길단위인구는 단위 미확인으로 분기 지표만 표시합니다. 전환점·반응·랭킹은 자리 표시만 있습니다.

## 검증 참고
카카오 SDK 실화면과 모바일/데스크톱 스크린샷은 브라우저 검증 대상입니다. 지도 없이도 연습 상권 선택 → 중심점 지정 → 창업까지 가능합니다. 본 화면은 데이터 출처를 표기하지만 목 모드의 모든 숫자는 실제 조회 데이터가 아닙니다.
