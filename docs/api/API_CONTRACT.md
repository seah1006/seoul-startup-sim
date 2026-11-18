# API 계약 v1.2 (프론트엔드 ↔ 백엔드)

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
  - 선택은 분기를 넘기지 않는다. 이후 쓰기에는 응답의 `game.version`을 사용한다. `advance`, `choice`, `sell` 사이에 같은 키를 재사용하면 409 `IDEMPOTENCY_KEY_REUSED`.

### 엔딩
- `POST /api/games/{gameId}/sell` (헤더 `Idempotency-Key`) 본문 `{ "expectedVersion": 3 }` → 매각 엔딩 `200 GameState` (status ENDED, endingType SOLD)
  - 영업 4분기 미만은 400, 전환점 대기 중에는 409 `TURNING_POINT_PENDING`.
- 자동 엔딩: CLOSED(잔액 2분기 연속 음수), LONG_RUNNING(데이터 마지막 분기 도달)
- `GET /api/games/{gameId}/summary` → 소유자만, 종료 전에는 409 `GAME_NOT_ENDED`. 종료 후:
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
    "entries": [ { "rank": 1, "gameId": 10, "nickname": "손님-4821", "ownerName": "김사장", "shopName": "망원커피",
                   "areaName": "망원역", "serviceName": "커피-음료", "endingType": "LONG_RUNNING",
                   "survivedQuarters": 14, "totalProfit": 182000000 } ] }
  ```
  - 순위: 생존 분기 수 → 누적 순이익 → 최고 분기 순이익 → 매장 수. 같으면 공동 순위. 30분마다 갱신. 날짜는 KST 오늘(기본) 포함 최근 7일만 조회 가능(범위 밖 400). `limit` 기본 50, 최대 100
- 홈 미리보기: `GET /api/rankings?kind=ALL&limit=3`

### 공유 페이지 (Thymeleaf)
- `GET /share/{gameId}` — 엔딩 요약 HTML, OG 태그(og:title, og:description, og:image는 정적 기본 이미지)

## 4. v1.2 추가 (커뮤니티 게시판) — 2026-11-18

> 기준 설계: `docs/DESIGN.md` 19장, DB: `docs/board/V11__board.sql`. 구현 지시서: `docs/tasks/TASK-005A.md`(게시판 코어), `TASK-005B.md`(RECORD·공유 연결), `TASK-005C.md`(React 화면).
> RECORD 작성과 `/api/board/records/{gameId}`는 엔딩 요약 원본(3장 `summary`, `game_records`)이 구현된 뒤 TASK-005B에서 연다. 그 전까지 `category: RECORD` 작성 요청은 400 `VALIDATION_FAILED`.

### 공통
- 사용자 화면은 React → `@RestController`, Base `/api`, JSON UTF-8. 필드명은 camelCase, 일시는 ISO 8601, 금액은 **원 단위 정수**(long). 본문은 일반 텍스트로 렌더링한다(HTML 삽입 금지).
- 게시판 조회는 공개. 모든 사용자 쓰기와 내 글 조회는 `X-Guest-Token` 필수(누락·무효 401 `UNAUTHORIZED`). 상세는 헤더 생략 가능하나 보낸 토큰이 무효면 401. 작성자는 본문의 값이 아니라 `GuestAuth.resolve()`의 `userId`로 판정한다. 정회원 전환 뒤에도 같은 `users.id`면 소유권 유지.
- 작성자만 글·댓글 수정/삭제 가능(다른 작성자 403 `FORBIDDEN`); 관리자 삭제는 `/admin/board/**`로 분리. 공개 글/댓글의 숨김·삭제·소속 경로 불일치는 404 `NOT_FOUND`(아래 댓글 자리표시자 제외).
- `page=0`(0부터, 최소 0), `size=20`(1~50). 목록 응답은 아래 `PageResponse<T>` 한 가지 형태. Spring `Pageable`을 사용하되 `Page` 직렬화 구조를 노출하지 않아 프론트가 Spring 버전과 무관하게 빈 목록·페이지 네비게이션을 처리한다. 범위 밖 페이지는 200 `content: []`(요청한 `page`, `size`와 실제 totals 유지), 결과 없음은 `totalElements: 0, totalPages: 0`.
- ID는 양의 정수, `version`은 0 이상의 정수. 필수 문자열은 양끝 공백 제거 후 `@NotBlank`와 길이 검증(`@Valid`); 정해지지 않은 enum, 음수 페이지·초과 크기·필수값 누락은 400 `VALIDATION_FAILED`. 요청에서 명시한 필드 외에는 저장하지 않는다.

### 판정 순서와 안전 규칙 (교차 리뷰 반영, 모든 게시판 쓰기 공통)
- **판정 순서 고정**: `401`(토큰) → `400`(형식 검증) → `404`(대상 없음·비공개·경로 불일치·남의 게임) → `403`(작성자 아님, 자기 글 신고) → `409`(버전·중복·상태). 조건부 UPDATE가 0행이면 대상을 다시 읽어 이 순서로 재판정한다(관리자가 방금 숨긴 글은 409가 아니라 404). 예외: 기존 상태를 읽어야 알 수 있는 400(예: 수정 시 RECORD ↔ 다른 카테고리 전환)은 403 다음, 409 전에 판정한다.
- **비공개 대상에는 아무것도 쓰지 않는다**: HIDDEN·DELETED 글(또는 그 글의 댓글)에 대한 좋아요·댓글·신고는 404이며 행과 카운터가 바뀌지 않는다.
- **카운터**: `like_count`·`comment_count`·`reply_count`·`view_count` 갱신은 `version`과 `updated_at`을 바꾸지 않는다. 감소는 `WHERE count > 0` 조건으로만 한다. 관리자가 댓글을 숨김·복구·삭제하면 같은 트랜잭션에서 글의 `comment_count`와 부모의 `reply_count`를 다시 맞춘다(ACTIVE 기준).
- **관리자 상태 전이**: ACTIVE → HIDDEN(숨김, 신고 처리), HIDDEN → ACTIVE(복구, `hidden_at` 초기화), ACTIVE·HIDDEN → DELETED(삭제, 복구 불가). 고정은 ACTIVE 글만. 신고 일괄 처리는 대상 행을 먼저 `FOR UPDATE`로 잠그고, 그다음 같은 대상의 PENDING 신고를 id 순으로 갱신한다(잠금 순서 고정).
- **검색 입력**: `q`의 `\`, `%`, `_`를 이스케이프하고 `ILIKE ? ESCAPE '\'`로 바인딩한다. 정렬·검색 종류는 enum 화이트리스트만 SQL에 반영한다. 1~2자 검색은 trgm 인덱스를 못 쓰고 순차 탐색이 될 수 있다(허용).
- **출력**: React는 본문을 텍스트로 렌더링한다. Thymeleaf(관리자, 공유 OG 메타, 플래시)는 `th:text`/`th:content`/`th:attr`처럼 이스케이프되는 속성만 쓰고 `th:utext`는 금지. `record.shareUrl`은 서버가 `/share/{gameId}` 형식으로만 만든다.
- **남용 방지(최소)**: 게시판 쓰기는 사용자당 분당 글 3, 댓글 10, 신고 5회까지(메모리 고정 창). 넘으면 `429 RATE_LIMITED`. 게스트 발급 제한(`POST /api/guest`)은 game 패키지 소관이라 후속. 게스트 토큰은 쿠키에 저장하지 않고 헤더로만 보내며, 토큰·신고자 정보는 로그에 남기지 않는다.
### 게시글 목록 (인증 불필요)
- `GET /api/board/posts?page=0&size=20&sort=popular&category=RECORD&q=첫+창업&searchType=titleContent&serviceCode=CS100010&areaCode=3110123` → `200 PageResponse<PostListItem>`
  - `category`: `FREE|TIP|QNA|RECORD`(생략하면 전체). `serviceCode`, `areaCode`: 각각 공백 제거 후 1~32자, 정확히 일치; 하나라도 쓰면 RECORD만 대상. 이때 명시한 `category`가 RECORD가 아니면 400. 등록된 스냅샷과 일치하는 코드가 없으면 빈 목록.
  - `q`: 공백 제거 후 1~100자(공백/빈 값은 검색 없음). `searchType`: `title|content|titleContent|author`, 기본 `titleContent`. 대소문자 구분 없는 리터럴 부분 일치(`\`, `%`, `_`는 이스케이프해 문자로 취급), `titleContent`는 제목 또는 본문, `author`는 현재 닉네임. 나머지 조건은 AND.
  - `sort`: `latest`(기본, 작성 시각 내림차순), `popular`(좋아요 수), `views`(조회수), `comments`(공개 댓글 수) 모두 내림차순. 선택 필터·검색에 맞는 고정 글 먼저(`isPinned DESC`), 이후 지정 정렬 → `createdAt DESC` → `postId DESC`. 고정 글도 총 건수와 페이지 크기에 포함한다.
  ```json
  {
    "content": [{ "postId": 42, "category": "RECORD", "title": "첫 창업 14분기 생존기",
      "authorNickname": "손님-4821", "createdAt": "2026-11-18T02:00:00Z", "updatedAt": "2026-11-18T02:00:00Z",
      "viewCount": 8, "likeCount": 2, "commentCount": 1, "isPinned": false,
      "record": { "gameId": 10, "endingType": "LONG_RUNNING", "serviceCode": "CS100010", "serviceName": "커피-음료",
        "areaCode": "3110123", "areaName": "망원역", "survivedQuarters": 14, "totalProfit": 182000000,
        "shopName": "망원커피", "ownerName": "김사장", "shareUrl": "/share/10" } }],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
  ```
  - `record`는 RECORD 외에는 null. 목록에는 `content`(게시글 본문)나 내부 사용자 ID가 없다.

### 게시글 상세
- `GET /api/board/posts/{postId}` → `200 PostDetail` (`PostListItem`의 모든 필드 + `content`, `version`, `likedByMe`, `isMine`). 무헤더면 개인 상태 둘 다 false. 개인 상태가 유출되지 않도록 상세 `Cache-Control: no-store`.
  ```json
  {
    "postId": 43, "category": "TIP", "title": "분기 예산 관리 팁", "content": "첫 분기에 쓸 예산을 미리 정하세요.",
    "authorNickname": "손님-4821", "createdAt": "2026-11-18T02:10:00Z", "updatedAt": "2026-11-18T02:10:00Z",
    "viewCount": 1, "likeCount": 0, "commentCount": 0, "isPinned": false,
    "record": null, "version": 0, "likedByMe": false, "isMine": true
  }
  ```
  - 상세 조회는 비로그인도 가능하지만 조회수는 유효한 토큰의 사용자만 글당 한국 시간(KST) 하루 1회 집계한다. `post_views`의 `(post_id,user_id,viewed_on)` 신규 삽입에 성공한 경우에만 같은 트랜잭션에서 조회수 +1. 무헤더 조회, 재조회, 목록·좋아요·작성/수정 응답·공유 미리보기는 증가시키지 않는다. 조회수와 카운터 갱신은 본문 `version`을 올리지 않는다.

### 게시글 작성·수정·삭제
- `POST /api/board/posts`
  ```json
  { "category": "TIP", "title": "분기 예산 관리 팁", "content": "첫 분기에 쓸 예산을 미리 정하세요." }
  ```
  → `201 PostDetail`, `Location: /api/board/posts/43`. 제목 1~100자, 본문 1~10000자. 일반 작성 POST는 멱등하지 않으므로 제출 중 버튼을 잠그고 결과 불명 시 자동 재전송하지 않는다.
- `PUT /api/board/posts/{postId}`
  ```json
  { "category": "TIP", "title": "분기 예산 관리 팁 (수정)", "content": "고정비도 먼저 확인하세요.", "version": 0 }
  ```
  → `200 PostDetail` (`version: 1`). 제목/본문/카테고리를 전부 보낸다. FREE/TIP/QNA 간 이동은 가능. RECORD 여부와 첨부 게임·스냅샷은 수정 불가(전환 또는 `gameId` 입력 400).
- `DELETE /api/board/posts/{postId}?version=1` → `204`(본문 없음). 논리 삭제하며 글과 하위 리소스는 공개·내 글에서 즉시 빠진다. 삭제 재요청은 404(상태 효과는 멱등).
- 수정·삭제: 인증 → 대상과 작성자 확인 → 클라이언트가 보낸 `version`으로 조건부 갱신. 이전 버전이면 `409 POST_VERSION_CONFLICT`; 최신 값을 재조회하고 사용자의 확인을 거쳐 재제출. 작성자라도 HIDDEN 글은 공개 REST로 수정/삭제하지 못한다(숨김 회피 방지).

### 내 글 (인증 필요)
- `GET /api/board/me/posts?page=0&size=20&category=RECORD` → `200 PageResponse<MyPostListItem>` (`PostListItem` + `status`, `version`). 공개 목록과 같은 필터/정렬, 인증 사용자의 `author_id`가 일치하는 ACTIVE·HIDDEN만 포함. 숨김 글은 상태만 안내하고 상세 링크·공유 버튼은 렌더링하지 않는다. `Cache-Control: no-store`.

### RECORD 작성 (종료 게임 첨부)
- `POST /api/board/posts`에 `category: RECORD`, `gameId` 필수(FREE/TIP/QNA에는 첨부 금지).
  ```json
  { "category": "RECORD", "gameId": 10, "title": "첫 창업 14분기 생존기", "content": "초반에는 고정비를 줄였습니다." }
  ```
  → `201 PostDetail`, `Location: /api/board/posts/42`. 클라이언트는 서버 응답의 `record`를 인증 카드로 표시한다. 제목·본문 외의 인증 값은 수정 불가.
  ```json
  {
    "postId": 42, "category": "RECORD", "title": "첫 창업 14분기 생존기", "content": "초반에는 고정비를 줄였습니다.",
    "authorNickname": "손님-4821", "createdAt": "2026-11-18T02:00:00Z", "updatedAt": "2026-11-18T02:00:00Z",
    "viewCount": 0, "likeCount": 0, "commentCount": 0, "isPinned": false, "version": 0, "likedByMe": false, "isMine": true,
    "record": { "gameId": 10, "endingType": "LONG_RUNNING", "serviceCode": "CS100010", "serviceName": "커피-음료",
      "areaCode": "3110123", "areaName": "망원역", "survivedQuarters": 14, "totalProfit": 182000000,
      "shopName": "망원커피", "ownerName": "김사장", "shareUrl": "/share/10" }
  }
  ```
  - 서버는 게임을 읽기 전용으로 조회한다. 게임 없음과 남의 게임은 모두 404 `NOT_FOUND`(다른 사람 게임의 존재를 노출하지 않는다), 본인 게임이 `ENDED`가 아니면 409 `RECORD_GAME_NOT_ENDED`. 요약 숫자·업종·상권·`endingType`과 플레이어가 정한 가상 가게/사장님 이름(`shopName`, `ownerName`)은 서버 원본에서 복사하며 3장 요약과 동일한 의미를 사용한다. 실제 상호를 인증 카드 예시의 소재로 차용하지 않는다. 종료가 확정됐는데 공식 엔딩 요약이 누락되면 임의 값으로 대체하거나 글을 생성하지 않는다(현 계약에서 500 `INTERNAL_ERROR`; 엔딩 원본이 비동기 생성되는 설계가 확정되면 대기 응답을 별도 합의).
  - 종료 게임 하나당 삭제되지 않은 RECORD 글 하나만 허용. soft delete 뒤 재작성 허용, HIDDEN이면 재작성 불가. 동시 생성도 DB 부분 유일 인덱스로 차단, 위반 시 409 `RECORD_ALREADY_POSTED`; 응답 분실 후 중복이면 내 글에서 확인한다.

### 엔딩 요약·공유 페이지 연결
- 3장의 소유자 전용 `GET /api/games/{gameId}/summary`는 그대로 쓴다. React 엔딩 화면은 이 요약으로 첨부 카드 미리보기(업종·상권 이름, 엔딩 종류, 생존 분기, 누적 순이익, 가게/사장님 이름)를 그리고 아래 연결 조회를 호출한다. 접수 전에 카드의 값과 이름이 공개된다고 안내한다. 코드 값은 사용자에게 입력받지 않는다. 공개 인증글이 없으면 작성 버튼을 제공한다(`/board/write?category=RECORD&gameId=10`에서 제목·본문을 입력하고 제출; 게임 정보는 조회로 채움). 자신의 게임에 HIDDEN 연결이 있으면 작성 대신 숨김으로 안내한다(내 글 목록 또는 작성 시 409).
- `GET /api/board/records/{gameId}` (인증 불필요) → `200` 연결된 공개 인증글 미리보기. 없거나 HIDDEN/DELETED이면 `204`(게임 존재·소유 여부는 이 경로에서 공개하지 않음). 숨김 후 예전 링크를 재사용하지 않도록 `Cache-Control: no-store`.
  ```json
  { "postId": 42, "title": "첫 창업 14분기 생존기", "commentCount": 1, "postUrl": "/board/posts/42" }
  ```
- `GET /share/{gameId}`는 기존 Thymeleaf/OG 계약 유지. 서버에서 같은 연결 조회 서비스를 호출해 공개 인증글 제목·댓글 수·링크를 넣는다(없으면 섹션 생략). 게스트 토큰, 운영 상세/원본 게임 상태는 공유하지 않는다. 인증글에서 `record.shareUrl`로 엔딩 공유 페이지에 돌아간다.

### 댓글·대댓글
- `GET /api/board/posts/{postId}/comments?page=0&size=20` → `200 PageResponse<CommentItem>`(루트만).
- `GET /api/board/posts/{postId}/comments/{commentId}/replies?page=0&size=20` → `200 PageResponse<CommentItem>`(해당 루트의 대댓글만). 정렬은 둘 다 `createdAt ASC, commentId ASC`. 공개 글이 아니거나 루트가 아닌 ID로 대댓글 목록을 요청하면 404.
  ```json
  {
    "content": [{ "commentId": 71, "parentId": null, "authorNickname": "손님-82ab", "content": "고정비 팁 감사합니다.",
      "createdAt": "2026-11-18T02:20:00Z", "updatedAt": "2026-11-18T02:20:00Z", "version": 0,
      "replyCount": 1, "isMine": false, "isPlaceholder": false }],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
  ```
  - 공개 대댓글이 남은 숨김/삭제 루트는 스레드 유지를 위해 `isPlaceholder: true`, `content: "표시할 수 없는 댓글입니다."`, `authorNickname: null`, `updatedAt: null`, `version: null`, `isMine: false`로 반환한다. 그 외 비공개 댓글은 목록에서 제외. `commentCount`는 ACTIVE 루트·대댓글 합계(자리표시자 제외), `replyCount`는 ACTIVE 대댓글 수, 대댓글 자체는 0. 선택 헤더 인증은 상세와 동일하며 목록에 `Cache-Control: no-store`.
- `POST /api/board/posts/{postId}/comments`
  ```json
  { "content": "읽어주셔서 감사합니다.", "parentId": 71 }
  ```
  → `201 CommentItem` (`isMine: true`, `version: 0`; 루트 댓글은 `parentId` 생략 또는 null). 본문 1~1000자. 부모는 같은 글의 ACTIVE 루트만 가능(없음·다른 글·비공개는 404, 대댓글을 부모로 지정하면 400 `COMMENT_DEPTH_EXCEEDED`). 일반 작성 POST는 멱등하지 않다.
- `PUT /api/board/posts/{postId}/comments/{commentId}` `{ "content": "감사합니다. 예산 표도 참고해 주세요.", "version": 0 }` → `200 CommentItem` (`version: 1`). 부모/소속 변경 불가.
- `DELETE /api/board/posts/{postId}/comments/{commentId}?version=1` → `204`, soft delete, 재요청은 404. 수정·삭제 모두 글과 동일한 검증 순서, 오래된 버전은 `409 COMMENT_VERSION_CONFLICT`.

### 좋아요
- `PUT /api/board/posts/{postId}/like` → `204` 좋아요 상태로 설정; `DELETE /api/board/posts/{postId}/like` → `204` 해제 상태로 설정(요청·응답 본문 없음).
- 둘 다 멱등: 이미 설정/해제된 상태에 같은 요청을 보내도 204. 토글 한 경로는 네트워크 재시도가 의도와 반대로 상태를 뒤집을 수 있어 쓰지 않는다(화면은 토글, 클라이언트는 요청을 직렬화). `UNIQUE(post_id,user_id)` 및 실제 삽입/삭제 행 수로 트랜잭션 안에서 카운트를 갱신한다. 클라이언트는 상태를 즉시 반영하고 필요 시 상세를 다시 조회한다.

### 신고
- `POST /api/board/reports`
  ```json
  { "targetType": "COMMENT", "targetId": 71, "reason": "ABUSE", "detail": "댓글에 모욕적인 표현이 있습니다." }
  ```
  → `201 { "reportId": 9, "status": "PENDING" }`. `targetType`: `POST|COMMENT`, `reason`: `SPAM|ABUSE|ADULT|ETC`, `detail`: 선택 최대 500자(`ETC`는 공백 제거 후 1자 이상). 판정 순서: 대상과 소속 글이 공개가 아니면 404 → 자신이 쓴 글·댓글이면 403 `FORBIDDEN` → 이미 신고했으면 409. `targetType`과 `targetId`가 실제 테이블에서 함께 확인돼야 한다(교차 조회 금지). 사용자당 동일 대상 한 번(처리 완료 뒤에도), 중복은 `409 ALREADY_REPORTED`. 접수 자체로 숨기지 않으며 신고자·신고 상세는 공개하지 않는다.

### 관리자 게시판 (Thymeleaf, `/api` 아님)
```
GET  /admin/board/posts                            게시글 관리 목록 (상태/카테고리/검색)
GET  /admin/board/posts/{postId}                   게시글·댓글 관리 상세
GET  /admin/board/reports                          신고 목록 (기본 PENDING; status 필터)
GET  /admin/board/reports/{reportId}                신고 상세
POST /admin/board/reports/{reportId}/resolve        신고 처리 (대상 숨김)
POST /admin/board/reports/{reportId}/dismiss        신고 기각 (가시성 변경 없음)
POST /admin/board/posts/{postId}/hide               게시글 숨김
POST /admin/board/posts/{postId}/restore            숨김 게시글 복구
POST /admin/board/posts/{postId}/delete             게시글 논리 삭제
POST /admin/board/posts/{postId}/pin                고정 상태 설정 (폼 pinned=true|false)
POST /admin/board/comments/{commentId}/hide         댓글 숨김
POST /admin/board/comments/{commentId}/restore      숨김 댓글 복구
POST /admin/board/comments/{commentId}/delete       댓글 논리 삭제
```
- 기존 `/admin/**` 보안 체인: 관리자 세션 및 `ROLE_ADMIN` 필요. 현재 운영 계정은 `AdminSecurityConfig`의 환경변수 기반 인메모리 관리자이며 `users.role` 및 게스트 토큰만으로 관리자 로그인이 되지 않는다. 미로그인은 `/admin/login` 리다이렉트, 역할/CSRF 실패는 현재 403. 모든 POST 폼은 CSRF 적용. `@Valid` + `BindingResult`로 입력 오류 시 400으로 폼을 다시 렌더링하고, 성공 시 `303` + 플래시 메시지로 상세/목록에 PRG. 관리자 조치는 대상 행 잠금 후 현재 상태와 허용 전이를 확인한다(폼에 버전 필드 없음). 이미 적용된 동일 조치의 반복은 상태/카운터를 중복 변경하지 않고, 상충하는 전이는 409 HTML 폼 오류.
- 신고는 `PENDING` → `RESOLVED`(대상이 ACTIVE면 HIDDEN으로 변경) 또는 `DISMISSED`. 경로의 신고 ID로 대상을 찾고 같은 대상의 PENDING 신고를 한 번에 처리한다. 처리 상태 변경과 숨김은 같은 트랜잭션에서 수행한다. 처리자 식별자는 세션 principal의 username을 `post_reports.handled_by`에 텍스트로 남기며 공개 API에 노출하지 않는다. HIDDEN만 RESTORE 가능하고 DELETED는 복구하지 않는다. 고정은 공개 글 대상으로만 허용(숨김 중에는 노출 안 됨). 기존 `notices`의 시스템/패치 공지와 별도이며, 게시판 고정 글은 `posts.is_pinned`만 사용한다. 숨김·복구·삭제 같은 상태 변경 시 해당 글/댓글 `version`도 증가한다.

### 공통 에러 코드 추가
- 기존 그대로: `VALIDATION_FAILED`(400), `UNAUTHORIZED`(401), `FORBIDDEN`(403), `NOT_FOUND`(404), `INTERNAL_ERROR`(500). `POST_NOT_FOUND`·`COMMENT_NOT_FOUND` 같은 리소스별 404는 추가하지 않는다.
- 추가: `RATE_LIMITED`(429), `COMMENT_DEPTH_EXCEEDED`(400), `POST_VERSION_CONFLICT`(409), `COMMENT_VERSION_CONFLICT`(409), `RECORD_GAME_NOT_ENDED`(409), `RECORD_ALREADY_POSTED`(409), `ALREADY_REPORTED`(409).
  ```json
  { "code": "POST_VERSION_CONFLICT", "message": "게시글이 변경되었습니다. 최신 게시글을 확인해 주세요.", "timestamp": "2026-11-18T02:30:00Z" }
  ```

### Spring 매핑
- `com.seah.startupsim.board`: `BoardPostController`, `BoardCommentController`, `BoardReportController`(`@RestController`), `AdminBoardController`(`@Controller`) → `BoardPostService`, `BoardCommentService`, `BoardLikeService`, `BoardReportService`, `BoardRecordService`, `BoardModerationService` → `BoardPostRepository`, `BoardCommentRepository`, `BoardLikeRepository`, `BoardReportRepository`, `BoardRecordReadRepository`. 공유 Thymeleaf는 `BoardRecordService`의 조회만 호출한다.
- 요청 record: `CreatePostRequest`, `UpdatePostRequest`, `CreateCommentRequest`, `UpdateCommentRequest`, `CreateReportRequest`, `PostSearchQuery`. 응답 record: `PageResponse<T>`, `PostListItem`, `PostDetail`, `MyPostListItem`, `RecordSnapshot`, `RecordPostLink`, `CommentItem`, `ReportCreated`.
- `BoardGuestAuth`에서 기존 `game.GuestAuth`를 주입해 `resolve()`만 위임한다. 인증이 필요한 메서드는 헤더를 선택 바인딩한 뒤 직접 위임하여 누락도 401로 통일. `GameException`은 보드 인증 예외(401)로 변환; 게시판 컨트롤러 한정 `BoardExceptionHandler`는 공통 advice보다 먼저 보드 예외만 잡아 공통 `ApiExceptionHandler.ApiError` 형식으로 반환한다. 바인딩/검증 오류는 기존 공통 처리, 관리자 폼은 HTML. 게임 패키지·기존 마이그레이션은 수정하지 않는다.
- `V11__board.sql`: `posts`, `comments`, `post_likes`, `post_reports`, `post_views`. `author_id`, `game_id`, `parent_id`, `reply_count`, `service_code`, `area_code`, `survived_quarters`, `total_profit`, `shop_name`, `owner_name`, `is_pinned`, `version`을 사용. 검증한 목록 파라미터로 `Pageable`을 만든다(임의 정렬 필드나 입력 자동 보정에 의존하지 않음). 글/댓글: `ACTIVE|HIDDEN|DELETED`; 신고: `PENDING|RESOLVED|DISMISSED`. 읽기 전용 `BoardRecordReadRepository`는 공식 엔딩 요약 원본과 게임 소유/종료 상태를 조회한다. 카운터·조회수 쓰기와 공개 대상 확인은 트랜잭션에서 처리한다.

### 후속으로 미룬 것
- 랭킹 행 → 인증글 연결: 3장 랭킹 응답에 `gameId`를 추가하고 일괄 조회로 N+1을 피하는 후속 계약.
- 이미지 업로드, 동네 반응 답글(선택 코드로 결정론 유지), 관리자 조치 감사 이력.
