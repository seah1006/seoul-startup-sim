# TASK-010: 진행 개편 — 매 분기 작은 사건, 실제 데이터 사건, 게임 목표(미션)

- 사용자 요청(2026-11-22): "진행이 단조롭다. 선택지를 주면서 흥미롭게." 범위 승인: ①매 분기 작은 사건 ②실제 데이터 사건 ③게임 목표. 오늘 구현·배포.
- 현재 문제: 12분기 완주 기준 전환점 평균 3.09회 → 나머지 약 9분기는 "운영 폼 → 영수증"만 반복.
- 목표: **선택이 없는 분기를 0~1회 수준**으로. 매 분기 최소 하나의 의미 있는 선택(작은 사건 또는 전환점).
- 브랜치: 백엔드 `task/TASK-010-be`(worktree `C:\Users\my\dev\sss-010b`), 프론트 `task/TASK-010-fe`(worktree `C:\Users\my\dev\sss-010f`). 두 작업은 아래 **API 계약 v1.4**만 맞추면 서로 기다리지 않는다.

## 1. 설계

### 1-1. 사건 등급(tier)
- 기존 전환점 테이블을 재사용한다. `turning_points`에 `tier text not null default 'MAJOR' check (tier in ('MAJOR','MINOR'))`, `source text not null default 'STORY' check (source in ('STORY','DATA'))` 추가 (V14).
- 기존 12개는 MAJOR/STORY 그대로. 새로 MINOR를 넣는다.
- 엔진 규칙(분기 시작 시 1회 판정, 기존 위치 그대로):
  1. MAJOR를 기존 규칙(확률·쿨다운·globalGap·globalCap)으로 판정.
  2. MAJOR가 안 나왔으면 MINOR 후보 중 하나를 고른다. 설정 `event.minor.rate`(기본 0.9) 확률로 발생. 후보 선택: 조건 통과 + 정의별 쿨다운 + `max_fires` 를 지키는 것 중 `priority`·확률 가중 결정론적 추첨(EventRandom 시드 스트림 사용, 기존 스트림과 분리된 새 스트림 이름).
  3. DATA 사건이 조건을 통과하면 STORY보다 우선(설정 `event.minor.dataFirst`=true). 한 게임 DATA 최대 `event.minor.dataMax`(기본 4)회.
  4. 분기당 사건은 최대 1개(MAJOR 또는 MINOR). pending 메커니즘·API 엔드포인트(`/turning-point`, `/turning-point/choice`)는 그대로 공유.
- 골든 재현성: 새 설정이 **없으면 MINOR는 꺼짐**(`event.minor.enabled` 기본값은 V14에서 true로 넣되, `GoldenSettings`는 V5~V13 설정 그대로라 골든 출력이 바뀌지 않아야 한다). 기존 골든 테스트 바이트 동일 유지가 완료 조건.

### 1-2. 작은 사건(MINOR/STORY) 콘텐츠 — 24개 이상
- 선택지 2~3개, 각각 장단점이 분명할 것(공짜 정답 금지). 효과는 작게: 현금 ±50~500만 원, 수정치(MODIFIER) ±3~15% 1~3분기, 능력치 ±1~3, FLAG.
- **지연 결과**: 최소 6개는 FLAG를 세우고, 1~3분기 뒤 그 FLAG를 조건으로 하는 후속 사건(체인)이 나온다. 예: "단골에게 외상 허용" → 2분기 뒤 "외상값을 갚으러 왔다/연락이 끊겼다".
- 업종 조건 활용(외식/커피/주점 등 serviceCode 그룹), 계절(분기 1~4), 직원 수, 가격 수준, 현금 상태 조건으로 다양화.
- 예시 주제(그대로 쓰지 않아도 됨): 단체 주문 문의, 직원 지각·이직 의사, 악성 리뷰, 재료 공급업체 가격 인상, 장마/폭염, 옆 가게 공사, 동네 축제 부스, 인근 회사 회식 단골, 위생 점검, 배달 라이더 불만, SNS 사진 유행 메뉴, 손님 분실물, 알바생 시험 기간, 건물주 수리 요청, 지역 상품권 가맹, 학생 할인 요청, 원두/주류 신제품 영업, 포인트 적립 도입, 설 연휴 영업 여부, 기부 요청.
- 실제 상호·브랜드·실존 인물 금지. 문구는 짧게(설명 120자 안팎), 존댓말 서술체 통일. 치환 변수 `{shopName:이/가}` 등 기존 EventText 규칙 사용.

### 1-3. 실제 데이터 사건(MINOR/DATA) — 8개 이상
- 조건 DSL에 **상권 데이터 변수**를 추가한다. 값은 게임에 고정된 데이터 버전(pins)의 해당 분기 vs 직전 분기, 이 게임 상권·업종 기준:
  - `area.salesChangePct` (같은 업종 분기 매출 증감률 %, 추정치면 null)
  - `area.storeChange` (같은 업종 점포 수 증감, 개)
  - `area.openings`, `area.closings` (같은 업종 개업·폐업 수)
  - `area.closingRatePct`, `area.openingRatePct`
  - `area.floatingPopChangePct` (길단위인구 증감률 %)
  - `area.franchiseChange`
  - null이면 그 조건은 false.
- 문구 치환 변수 추가: `{area.quarterLabel}`(예: 2022년 3분기), `{area.salesChangePct}`, `{area.storeChange}`, `{area.openings}`, `{area.closings}`, `{area.floatingPopChangePct}` — 숫자는 부호 포함 정수(예: "-12"). EventText 허용 목록 확장, 검증기(EventDefinitionValidator)도 갱신.
- 예: "실제로 {area.quarterLabel} 이 상권 {serviceName} 매출이 {area.salesChangePct}% 줄었습니다" → 선택: 가격 인하 / 메뉴 정비 / 버티기. "같은 업종 가게가 {area.openings}곳 새로 열었습니다" → 차별화 마케팅 / 단골 이벤트 / 무시. "유동인구가 {area.floatingPopChangePct}% 늘었습니다" → 영업시간 연장 / 직원 추가 / 현상 유지. 폐업 급증, 프랜차이즈 진입 등.
- DATA 사건 응답에 `dataNote`: "서울시 상권분석서비스 {area.quarterLabel} 실제 값" — 화면에 출처로 표시.
- 효과도 데이터와 정합: 매출 감소 사건의 "버티기"는 실제 하락이 이미 계산에 반영된다는 점을 문구로 알려 줄 것(이중 반영 금지 — 효과는 플레이어 대응의 결과만).

### 1-4. 게임 목표(미션)
- 게임 생성 시 목표 하나 선택(선택 안 하면 `SURVIVE_3Y`). 코드 고정 5개(DB 테이블 불필요, 코드 상수 + 표시 문구):
  | code | 제목 | 달성 조건 |
  |---|---|---|
  | `SURVIVE_3Y` | 3년 버티기 | 12분기 영업 |
  | `PROFIT_100M` | 누적 순이익 1억 | 누적 순이익 ≥ 1억 원 |
  | `AREA_TOP3` | 동네 3위 안 | 상권 내 같은 업종 순위 3위 이내 도달 |
  | `BLACK_4` | 4분기 연속 흑자 | 순이익 > 0 4분기 연속 |
  | `SECOND_SHOP` | 2호점 열기 | 매장 수 ≥ 2 |
- `games`에 `mission_code text not null default 'SURVIVE_3Y'`, `mission_achieved_turn int null` (V14). 분기 정산 때 판정, 한 번 달성하면 유지.
- 달성 시 그 분기 결산에 `missionEvent: { "code", "title", "text": "목표 달성! …" }`.
- 엔딩 기록(`game_records`)에 `mission_code`, `mission_achieved` 저장 → 요약·공유 페이지·랭킹 응답에 노출. **랭킹 정렬은 바꾸지 않는다**(표시만).
- 밸런스: 표준 정책 봇으로 각 목표 달성률을 측정해 `docs/balance/MISSION_REPORT.md`에. 목표 달성률이 0%나 90% 이상이면 조건 숫자를 조정.

## 2. API 계약 v1.4 (docs/api/API_CONTRACT.md 6장으로 추가)
- `GET /api/missions` (인증 불필요) → `[{ "code": "SURVIVE_3Y", "title": "3년 버티기", "description": "12분기 동안 가게를 지키세요.", "targetLabel": "12분기" }, …]` 5개, 위 표 순서.
- `POST /api/games` 본문에 `missionCode`(선택, 위 5개 중 하나, 그 외 400 `VALIDATION_FAILED`).
- `GameState`에 추가:
  ```json
  "mission": { "code": "PROFIT_100M", "title": "누적 순이익 1억", "description": "…",
               "progress": { "current": 38200000, "target": 100000000, "unit": "KRW", "label": "3,820만 원 / 1억 원" },
               "achieved": false, "achievedTurn": null }
  ```
  unit: `KRW | QUARTER | RANK | STREAK | SHOP`. RANK는 current=현재 순위(없으면 null), target=3.
- 전환점 응답(`GET /turning-point`)에 `"tier": "MAJOR"|"MINOR"`, `"source": "STORY"|"DATA"`, `"dataNote": string|null` 추가. 나머지 형식 동일.
- `QuarterResult`에 `"missionEvent": null | { "code", "title", "text" }`.
- 엔딩 요약에 `"mission": { "code", "title", "achieved": true, "achievedTurn": 7 }`, 랭킹 `entries[]`에 `"missionCode"`, `"missionTitle"`, `"missionAchieved"`.

## 3. 백엔드 (task/TASK-010-be)
- V14 마이그레이션: 컬럼 추가, 설정(`event.minor.enabled`=true, `event.minor.rate`=0.9, `event.minor.dataFirst`=true, `event.minor.dataMax`=4), MINOR 콘텐츠 INSERT(STORY 24+, DATA 8+, 체인 후속 포함). 기존 V1~V13 수정 금지.
- 엔진(EventEngine/EventContextFactory/EventDsl/EventText/Validator), 미션 판정(분기 정산 경로, AdvanceService 근처), 엔딩 기록·요약·공유 템플릿(share/game.html에 "목표: 누적 순이익 1억 · 달성" 한 줄)·랭킹 응답, 관리자 전환점 편집 화면에 tier/source 표시·편집.
- 테스트: 기존 219+ 전부 통과, 골든 바이트 동일, 새 테스트(MINOR 발생·DATA 변수·치환·체인 FLAG·미션 5종 판정·결정론 재현·검증기 거부 케이스).
- 밸런스: 기존 시뮬레이터로 V14 적용 후 식품 업종 표준 정책 생존율이 41~75% 범위 유지인지, 선택 없는 분기 비율(목표 ≤10%)·게임당 사건 수를 `docs/balance/EVENT_BALANCE_REPORT.md`에 추가.
- 실DB 리허설: 메인 DB(5433)에 직접 쓰지 말고, Testcontainers 또는 별도 리허설 DB로 V14 적용 확인.

## 4. 프론트엔드 (task/TASK-010-fe)
- 목 데이터(`src/api/mock`)를 계약 v1.4대로 먼저 확장: missions 목록, GameState.mission, MINOR/DATA 사건 몇 개(매 분기 하나씩 나오도록), missionEvent.
- `/new`: 집중 능력치 아래 **"이번 창업의 목표"** 단계 — 5개 목표를 괘선 리스트/토글로(골목 장부 디자인 규칙 TASK-009 그대로: 각진 모서리, 노란 선택 배경, eyebrow 금지). 기본 선택 SURVIVE_3Y.
- 게임 화면 헤더(가게 이름 아래): **목표 진행 줄** — "목표 · 누적 순이익 1억  3,820만 원 / 1억 원" + 얇은 진행 막대(--ink), 달성 시 빨간 도장 "달성".
- 사건 카드: MAJOR는 지금 전환점 카드 그대로. MINOR는 더 작은 카드(설명 + 선택지 버튼 2~3개 가로, 결과 한 줄). DATA는 카드 위에 "실제 데이터" 태그 + 아래 `dataNote` 출처(작은 글씨). 확률 막대는 MINOR에서는 숨기고 "성공 확률 70%" 텍스트만.
- 선택 후 결과(성공/실패 문구·효과)를 같은 카드 안에 보여 준 뒤 "운영 계획으로" 버튼.
- 분기 결산 영수증 아래 `missionEvent`가 있으면 "목표 달성" 배너(도장).
- 엔딩 요약·랭킹 표(데스크톱 열/모바일 카드)에 목표와 달성 여부 표시.
- 테스트: 기존 83 통과 + 새 테스트(목표 선택이 생성 요청에 실림, 진행 줄, MINOR/DATA 카드 렌더, missionEvent). lint·prettier·build.

## 5. 공통 규칙
- 비밀값 금지, 실존 상호 금지(사실 정보 외), push 금지, 의미 단위 커밋.
- 한 번 실행 약 10분 제한 → 단계별 커밋, 제한 시 남은 단계 보고.
