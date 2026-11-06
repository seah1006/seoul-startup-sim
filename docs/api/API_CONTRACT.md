# API 계약 v1 (프론트엔드 ↔ 백엔드)

> 프론트엔드와 백엔드가 동시에 개발할 수 있도록 먼저 정한 계약입니다. 바꿀 때는 이 문서를 먼저 고치고 커밋합니다.
> 기준 설계: `docs/DESIGN.md` 2~8장, 12장.

## 공통

- Base: `/api`. JSON, UTF-8. 금액은 **원 단위 정수**(long). 비율은 0~1 실수(소수 4자리) 또는 정수 %로 필드명에 `Pct`.
- 분기 코드: `"20262"` 형식 문자열 (YYYYQ).
- 에러 형식 (모든 4xx/5xx):
  ```json
  { "code": "GAME_VERSION_CONFLICT", "message": "사람이 읽는 한국어 메시지", "timestamp": "2026-11-07T01:00:00Z" }
  ```
- 주요 에러 코드: `VALIDATION_FAILED`(400), `UNAUTHORIZED`(401), `FORBIDDEN`(403), `NOT_FOUND`(404), `GAME_VERSION_CONFLICT`(409), `TURN_ALREADY_ADVANCED`(409), `GAME_ENDED`(409), `INTERNAL_ERROR`(500)

### 게스트 인증
- `POST /api/guest` → `201 { "guestToken": "…", "userId": 1, "nickname": "손님-4821" }`
- 이후 게임 API는 헤더 `X-Guest-Token: …` 필수. 없거나 틀리면 401. 다른 사람 게임이면 403.
- 토큰은 서버에 해시로만 저장. (카카오 로그인은 이후 작업에서 같은 users 행을 정회원으로 전환)

## 1. 카탈로그 (인증 불필요)

### 상권 지도 (구현됨, TASK-002A)
- `GET /api/areas?bbox=minLng,minLat,maxLng,maxLat&zoom=15` → GeoJSON FeatureCollection
  - feature.properties: `areaCode, name, areaTypeName, districtName`
  - 응답 최상위에 `truncated: boolean` (최대 개수 초과 여부)
- `GET /api/areas/search?q=망원` → `[{ areaCode, name, areaTypeName, districtName, centroid: {lng, lat} }]` (최대 20)
- `GET /api/areas/{areaCode}` → `{ areaCode, name, areaTypeName, districtName, dongName, areaM2, centroid: {lng, lat} }`

> 위 세 API의 실제 필드명이 이 문서와 다르면, 구현을 이 문서에 맞춘다(camelCase).

### 업종
- `GET /api/industries?playable=true` → `[{ serviceCode: "CS100010", name: "커피-음료", category: "FOOD", playable: true }]`
  - v1의 `playable`은 외식(FOOD) 10개이면서 매출 자료가 있는 업종

### 상권 리포트
- `GET /api/areas/{areaCode}/report?quarter=20262` → 
  ```json
  {
    "areaCode": "3110xxx", "name": "망원동 상권", "quarter": "20262",
    "floatingPopulation": 433442,            // 길단위인구 합계 (단위 미확인: 화면에 '분기 지표'로만 표시)
    "industries": [
      { "serviceCode": "CS100010", "name": "커피-음료",
        "storeCount": 42, "franchiseCount": 9, "openingRatePct": 3, "closingRatePct": 2,
        "quarterSales": 1820000000,           // 분기 3개월 합계(원). 자료 없으면 null
        "salesEstimated": false }            // true면 자치구 평균으로 추정한 값
    ]
  }
  ```
  - quarter 생략 시 가장 최신 ACTIVE 분기
- `GET /api/areas/{areaCode}/stores?serviceCode=CS100010&lng=126.905&lat=37.556&radius=300`
  → `{ "count": 12, "stores": [{ "storeId", "name", "lng", "lat", "distanceM" }] }`
  - 실제 상호는 **사실 정보로만** 표시 (AGENTS.md 규칙 6). 최대 100건.

## 2. 게임

### 사장님 후보
- `GET /api/owners/candidates?background=OFFICE_WORKER&seed=123`
  - background: `OFFICE_WORKER`(전직 직장인), `CULINARY`(요리학교 출신), `PART_TIMER`(알바 10년차), `LOCAL`(동네 토박이)
  - → `{ "background": "OFFICE_WORKER", "candidates": [ { "candidateId": "c1", "stats": { "cooking": 35, "service": 40, "management": 50, "marketing": 48, "stamina": 37, "network": 30 }, "total": 240 }, … 3개 ] }`
  - 모든 후보 total = 240. 같은 background·seed면 같은 후보.

### 게임 생성
- `POST /api/games` 
  ```json
  { "ownerName": "김사장", "background": "OFFICE_WORKER", "candidateId": "c1", "candidateSeed": 123,
    "focusStats": ["marketing", "service"], "startQuarter": "20231" }
  ```
  → `201 GameState` (아래). startQuarter 생략 시 서버가 고름. focusStats 0~2개.

### 창업
- `POST /api/games/{gameId}/shop`
  ```json
  { "areaCode": "3110xxx", "serviceCode": "CS100010", "lng": 126.905, "lat": 37.556, "shopName": "망원커피" }
  ```
  → `200 GameState`. 위치는 해당 상권 폴리곤 안이어야 함(아니면 400). 이때 반경 내 실제 같은 업종 점포가 rivals로 복사된다.

### 분기 운영 결정
- `PUT /api/games/{gameId}/turns/{turn}/decision`
  ```json
  { "priceLevel": "NORMAL", "openHours": 10, "staff": 2, "marketingBudget": 3000000, "ownerActivity": "MENU_DEV" }
  ```
  - priceLevel: `LOW|NORMAL|HIGH`, openHours: 6~16, staff: 0~10, ownerActivity: `MENU_DEV|REGULARS|STUDY|REST`
  - → `200 { "turn": 0, "saved": true }`. 이미 진행된 turn이면 409 `TURN_ALREADY_ADVANCED`.

### 다음 분기 진행
- `POST /api/games/{gameId}/advance` 헤더 `Idempotency-Key: <uuid>`, 본문 `{ "expectedVersion": 3 }`
  - 이번 turn의 decision이 없으면 400
  - version 불일치 → 409 `GAME_VERSION_CONFLICT`
  - 같은 Idempotency-Key 재요청 → 처음 결과를 그대로 다시 반환(200), 두 번 진행되지 않음
  - → `200 { "result": QuarterResult, "game": GameState }`

### 조회
- `GET /api/games/{gameId}` → `GameState`
- `GET /api/games/{gameId}/turns` → `[QuarterResult]` (turn 오름차순)

### 타입

`GameState`
```json
{
  "gameId": 10, "version": 3, "status": "PLAYING",        // PLAYING | ENDED
  "endingType": null,                                      // CLOSED | SOLD | LONG_RUNNING | null
  "currentTurn": 2, "currentQuarter": "20233", "startQuarter": "20231", "lastQuarter": "20262",
  "cash": 48200000,
  "owner": { "name": "김사장", "background": "OFFICE_WORKER",
             "stats": { "cooking": 37, "service": 44, "management": 50, "marketing": 52, "stamina": 37, "network": 30 },
             "focusStats": ["marketing", "service"] },
  "shop": null 또는 { "areaCode", "areaName", "serviceCode", "serviceName", "shopName", "lng", "lat", "rivalCount": 12 },
  "pendingDecision": true                                  // 이번 turn 결정이 아직 없으면 true
}
```

`QuarterResult` (분기 결산 카드)
```json
{
  "turn": 1, "quarter": "20232",
  "headline": { "label": "이번 분기 순이익", "amount": 12400000 },
  "revenue": 58200000, "visitors": 4130, "avgTicket": 14100,
  "costs": { "rent": 9000000, "ingredients": 20370000, "labor": 12000000, "marketing": 3000000, "total": 44370000 },
  "profit": 13830000, "cashAfter": 62030000,
  "areaRank": { "rank": 4, "of": 12 },
  "statChanges": [ { "stat": "cooking", "before": 49, "after": 52 } ],
  "areaNews": { "openings": 2, "closings": 1, "source": "서울시 상권분석서비스 20232" },
  "moment": null,              // 이후 작업(전환점/이벤트)에서 채움
  "reactions": [],             // 이후 작업(동네 반응)에서 채움
  "dataNotes": ["이 상권의 커피-음료 매출은 자치구 평균으로 추정한 값입니다"]
}
```

## 3. v1.1 추가 (전환점, 동네 반응, 엔딩, 랭킹) — 2026-11-07

### GameState 추가 필드
- `pendingTurningPoint: boolean` — true면 전환점을 먼저 선택해야 다음 분기 진행 가능. 이 상태에서 advance 요청은 409 `TURNING_POINT_PENDING`

### QuarterResult 필드 채움
- `moment`: `{ "title": "단골손님이 친구들을 데려왔어요", "text": "…", "stat": "service", "success": true }` 또는 null
- `reactions`: `[{ "kind": "REVIEW", "author": "망원동 주민", "rating": 4, "text": "…", "likes": 12 }, { "kind": "COMMUNITY", "author": "동네 커뮤니티", "text": "…", "likes": 31 }]` — 게임 속 가상 반응. 실제 상호 사용 금지

### 전환점
- `GET /api/games/{gameId}/turning-point` → 대기 중이면 200, 없으면 204
  ```json
  { "gameTurningPointId": 7, "code": "FRANCHISE_OFFER", "title": "프랜차이즈 가맹 제안",
    "description": "…", "options": [
      { "optionId": 1, "label": "가맹한다", "successRatePct": 70,
        "onSuccess": "매출 +20%, 원가율 +5%p", "onFail": "가맹비 3,000만 원만 나간다" },
      { "optionId": 2, "label": "독립 가게로 남는다", "successRatePct": 100, "onSuccess": "변화 없음", "onFail": "-" } ] }
  ```
- `POST /api/games/{gameId}/turning-point/choice` `{ "optionId": 1 }` 헤더 `Idempotency-Key`
  → `200 { "success": true, "resultText": "…", "effects": [{ "label": "매출", "value": "+20%" }], "game": GameState }`

### 엔딩
- `POST /api/games/{gameId}/sell` (헤더 `Idempotency-Key`) → 매각 엔딩 `200 GameState` (status ENDED, endingType SOLD)
- 자동 엔딩: CLOSED(잔액 2분기 연속 음수), LONG_RUNNING(데이터 마지막 분기 도달)
- `GET /api/games/{gameId}/summary` →
  ```json
  { "gameId": 10, "endingType": "LONG_RUNNING", "ownerName": "김사장", "shopName": "망원커피",
    "areaName": "망원역", "serviceName": "커피-음료", "survivedQuarters": 14,
    "totalProfit": 182000000, "bestQuarterProfit": 31000000, "shopCount": 1,
    "shareUrl": "/share/10" }
  ```

### 랭킹 (인증 불필요)
- `GET /api/rankings?date=2026-11-07&kind=ALL` (kind: `ALL|INDUSTRY|AREA`, INDUSTRY/AREA는 `key`=serviceCode/areaCode)
  ```json
  { "date": "2026-11-07", "refreshedAt": "2026-11-07T10:30:00+09:00", "kind": "ALL",
    "entries": [ { "rank": 1, "nickname": "손님-4821", "ownerName": "김사장", "shopName": "망원커피",
                   "areaName": "망원역", "serviceName": "커피-음료", "endingType": "LONG_RUNNING",
                   "survivedQuarters": 14, "totalProfit": 182000000 } ] }
  ```
  - 순위: 생존 분기 수 → 누적 순이익 → 최고 분기 순이익 → 매장 수. 같으면 공동 순위. 30분마다 갱신. 최근 7일 조회 가능
- 홈 미리보기: `GET /api/rankings?kind=ALL&limit=3`

### 공유 페이지 (Thymeleaf)
- `GET /share/{gameId}` — 엔딩 요약 HTML, OG 태그(og:title, og:description, og:image는 정적 기본 이미지)
