# REVIEW-2A: TASK-010 진행 개편 1단계(규칙 기반) 리뷰

- 범위: `907f8b8..175ebb8` (MINOR 사건 엔진, DATA 변수, 게임 목표, V14/V14_1/V14_2, 프론트 목표·사건 카드)
- 기준: `docs/REVIEW_GUIDE.md`. 참고: `docs/tasks/TASK-010.md`, `docs/api/API_CONTRACT.md` 6장, `AGENTS.md`, Open Code Review `delegate preview` 파일 목록(리뷰 대상 39개)
- 방식: 읽기 전용. 테스트는 새로 돌리지 않음(메인 확인 245/91 통과 기준). 줄 번호는 `175ebb8` 기준.
- 결론: **P0 없음.** 재현성·Flyway·pins·권한·멱등은 규칙을 지킨다. 다만 화면에 보이는 효과 수치 불일치, 목표 판정 경계(2호점), 기존 기록 목표 표시, 사건 콘텐츠의 공짜 선택지가 P1로 남는다.

## 중점 항목 확인 결과 (문제 없음으로 본 것)

- **재현성**: 새 난수는 `EventRandom.Purpose.MINOR_FIRE`, `MINOR_PICK`(EventRandom.java:15)으로 기존 스트림과 분리. `pickMinor`는 게임 설정 스냅샷에 `event.minor.enabled`가 없으면 즉시 null(EventEngine.java:158)이라 V14 이전에 만든 진행 중 게임과 `GoldenSettings`는 MINOR가 꺼진다. 후보 정렬은 code 순(EventEngine.java:183)이라 입력 순서와 무관. `event.fire.minTurn` 검사를 MAJOR 필터로 옮긴 것(EventEngine.java:78-79)과 `history.majorQuartersSince` 대체(EventEngine.java:126, EventContextFactory.java:95-102)는 MINOR가 없을 때 이전 식과 같은 값이다. 골든 파일 변경 없음.
- **Flyway**: V14·V14_1은 병합(337304a) 전 작업 브랜치에서만 수정됨(4d62642→5383e29→4743ab5, c8195b5→4743ab5). 병합 뒤 라벨 수정은 V14_1을 고치지 않고 새 파일 V14_2로 추가해 규칙대로다. 버전 순서 14 < 14.1 < 14.2.
- **pins 고정·null**: DATA 변수 쿼리 3개 모두 현재·직전 분기 행에 `game_data_pins` EXISTS를 건다(EventContextFactory.java:110-142). null은 `put`/`change`에서 넣지 않고, DSL 비교는 변수가 없으면 false(EventDsl Comparison). 분모 0·음수 거부(EventContextFactory.java:145-148). 추정 경로 사용 없음.
- **권한**: `/api/missions`는 계약대로 공개(ApiSecurityConfig anyRequest permitAll). 목표 상태는 소유자 검사를 거친 `GameRepository.state` 안에서만 조회. 사건 조회·선택 엔드포인트는 변경 없음. 관리자 편집은 `/admin/**` ADMIN 보호 유지.
- **동시성·멱등**: 목표 판정은 `advance`의 행 잠금 트랜잭션 안에서 `mission_achieved_turn IS NULL` 조건부 UPDATE(MissionRepository.java:32). 멱등 재요청은 캐시된 응답에 `missionEvent`까지 포함(EventFlowIntegrationTest `selectedMissionAchievesInsideSettlementAndIsCached`). 사건 선택 경로는 기존 버전·키 검사 그대로.
- **실제 상호**: V14_1 문구 48개에 실존 상호·브랜드·인물 없음(보습학원, 지역아동센터, 부동산 사장님 등 일반 명사).
- **체인 FLAG**: 체인 후속 8개 모두 모든 선택지에서 FLAG를 false로 지우고 `max_fires=1`, 선행 사건도 `max_fires=1`이라 반복되지 않는다.
- **연속 흑자 리셋**: `streak`는 최신 분기부터 `profit <= 0`에서 멈춘다(MissionRepository.java:24-27). 0원은 끊김으로 계약과 일치.

---

## P0

없음.

---

## P1

[P1] backend/src/main/java/com/seah/startupsim/game/EventEffectService.java:106-114 — 선택 결과 칩의 수정치 값이 V14_2 라벨과 다르게 표시됨
  근거: V14_1:254-264가 MINOR 수정치를 25%로 줄여 값이 1.005, 0.995, 0.0025 같은 소수가 됐다. V14_2는 라벨을 소수 첫째 자리로 다시 썼지만(`방문객 -0.5% · 1분기`), 선택 후 `describe`의 값은 `Math.round`로 정수 %를 만든다(108행 `%+d%%`, 111행 `%+d%%p`). 예: MI_RAINY_SEASON 2번(visitors 0.98→0.995)은 `Math.round(-0.5)=0`이라 칩이 "방문객 -0.5% · 1분기 **+0%**"로 나온다. costRate 0.01→0.0025는 "원가율 +0.3%p · 2분기 **+0%p**", 1.06→1.015는 "+1.5% … **+2%**". 프론트는 label과 value를 나란히 보여 준다(GameMilestones.tsx:66-69). REVIEW_GUIDE P1 "효과 라벨과 실제 효과 수치가 다르다"에 해당하고, 공개 서버에서 이미 보인다.
  제안: `modifierValue`를 소수 첫째 자리 형식으로 바꾸거나(V14_2 SQL과 같은 반올림), 라벨에 수치가 있는 MODIFIER는 value를 빈 문자열로 두고 라벨만 보여 준다. 칩 문자열 회귀 테스트를 추가한다.

[P1] backend/src/main/resources/db/migration/V14_1__minor_event_content.sql:159 — 25% 축소 뒤 문구 속 금액이 실제 효과와 어긋남
  근거: MI_CREDIT_TAB 설명은 "80만 원 정도가 될 것 같습니다"인데, 254-264행 일괄 축소로 1번 "외상 허락" 현금은 -20만 원, 2번 "절반만"은 -12.5만 원이다(선택지 요약에는 축소된 금액이 표시되므로 같은 카드 안에서 설명과 숫자가 충돌한다). 15행 헤더 주석("현금 ±50만~500만 원, MODIFIER ±2~10%")도 축소 전 값이다. 확인 필요(서사 수준): MI_STUDENT_DISCOUNT "10% 깎아"(121행) ↔ 원가 +0.5%p, MI_RAINY_SEASON 성공문 "포장 10% 할인"(50행).
  제안: 새 마이그레이션(V14_3)으로 MI_CREDIT_TAB 설명의 금액을 "20만 원 정도"로 고치거나 금액을 문구에서 뺀다. V14_1 헤더 주석은 파일 수정 금지라 문서(EVENT_BALANCE_REPORT)에 실제 범위를 적는다.

[P1] backend/src/main/resources/db/migration/V14_1__minor_event_content.sql:80, 234 — 단점이 없는 공짜 정답 선택지
  근거: (1) MI_OFFICE_DINNER 3번 "마감 시간은 지키겠다고 안내"(80행)는 성공률 100%에 현금 +80만(축소 후 +20만)과 체력 +1, 손해 효과가 없다. 1·2번보다 위험이 없고 보상만 있다. (2) MD_FOOT_TRAFFIC_DOWN 2번 "남은 동네 단골에 집중"(234행)은 성공률 100%에 서비스 +2·네트워크 +1(상한 적용 후 +1·+1), 비용이 없다. REVIEW_GUIDE P1 "공짜 정답 선택지".
  제안: (1)에 `network -1` 또는 `marketing -1`을 붙이고, (2)에 소액 현금 비용이나 방문객 -1% 수정치를 붙인다(V14_3 UPDATE). 아래 P2의 "현상 유지 +1 능력치" 패턴도 함께 정리하는 것을 권한다.

[P1] backend/src/main/java/com/seah/startupsim/game/MissionRepository.java:17, 29-34 — 2호점 목표가 다음 정산에서야 판정되고, 매각하면 달성이 사라짐
  근거: `shop_count`는 전환점 선택(`EventEffectService` SHOP_COUNT, 56행) 시점에 바뀌지만 판정은 `AdvanceService.java:52`의 정산 경로에서만 한다. 그래서 (a) `achievedTurn`이 실제보다 1분기 늦고, (b) 선택 직후 GameState는 progress "2 / 2곳"인데 achieved=false라 화면에 진행 막대 100%와 "달성" 도장 없음이 함께 보이며, (c) 그 상태에서 매각하면 `EndingService.java:68` → `GameRecordRepository.java:50`이 `mission_achieved_turn IS NOT NULL`만 보므로 기록·랭킹·공유에 "미달성"으로 남는다. 다른 4개 목표는 정산 값만 보므로 영향 없음.
  제안: `TurningPointService.choose`에서 효과 적용 뒤 `MissionRepository.evaluate(jdbc, id, pending.turn())`를 호출하거나, 최소한 매각 직전에 판정한다. 매각 경로 테스트를 추가한다.

[P1] backend/src/main/resources/db/migration/V14__minor_events_missions.sql:9-12 — V14 이전 엔딩 기록이 "3년 버티기 · 미달성"으로 표시됨
  근거: `game_records.mission_code DEFAULT 'SURVIVE_3Y'`, `mission_achieved DEFAULT false`로 기존 행이 채워진다. 백필이 없어 목표 개념이 없던 기존 기록이 랭킹(RankingRepository.java:39, Rankings.tsx:53-58)과 공유 페이지(share/game.html:35)에 "미달성"으로 나온다. 12분기를 채운 LONG_RUNNING 기록도 미달성으로 보인다. 사실과 다른 표시라 실데이터 정직성 P1.
  제안: V14는 배포됐으므로 새 마이그레이션으로 `survived_quarters >= 12`인 기존 기록의 `mission_achieved`를 true로 백필하거나, 기존 행을 "목표 없음"(nullable 또는 별도 플래그)으로 구분해 화면에서 숨긴다.

[P1] frontend/src/api/mock/index.ts:117 — 목 데이터가 실제 목표 응답과 다름
  근거: `initialMission`의 target 배열에서 AREA_TOP3가 `3`이다(백엔드 Mission.java:9는 5, 계약 6.2도 5). 진행 막대는 target으로 계산하므로(GamePage.tsx MissionProgress) 목 모드에서 4~5위가 100%가 아닌 값으로 그려진다. 문구도 다르다: description(30행 "모으세요" ↔ "달성하세요", 48행 "두 번째 매장을 여세요" ↔ "매장을 2곳 이상으로 늘리세요"), targetLabel(49행 "2개 매장" ↔ "2곳"), progress.label("3분기 / 12분기", "1개 / 2개 매장" ↔ 백엔드 "3 / 12분기", "1 / 2곳"). 또 613행은 창업 직후(첫 정산 전) 사건을 대기시키는데, 백엔드는 정산 뒤 `afterQuarter`에서만 사건을 만든다.
  제안: 목 target을 5로 고치고, 문구와 라벨을 `Mission.java`·`Mission.label`과 같게 맞춘다. 창업 직후 대기 사건은 없앤다.

[P1] backend/src/main/java/com/seah/startupsim/game/EventDefinitionValidator.java:255-290 — 반응·순간 문구가 DATA 치환 변수를 통과시키지만 렌더링하지 않음
  근거: `tokens()`는 `EventText.withoutTokens`(EventText.java:63)로 검사하는데, 이제 `{area.openings}` 같은 DATA 토큰도 지운다. 반응·순간은 `NeighborhoodEventService.java:174`에서 2인자 `EventText.render(text, shop)`로 렌더링하므로 DATA 토큰이 치환되지 않는다. 관리자가 반응 본문에 `{area.openings}`를 넣으면 검증을 통과하고 화면에 중괄호가 그대로 나온다.
  제안: `tokens(text, path, allowData)`로 나눠 전환점(title/description/label/결과문)만 DATA 토큰을 허용한다. 거부 테스트를 추가한다.

[P1] backend/src/main/java/com/seah/startupsim/game/EventDefinitionValidator.java:230-231 — tier/source 조합과 DATA 근거를 검증하지 않음
  근거: 관리자는 아무 MAJOR나 STORY 사건을 `source=DATA`로 바꿀 수 있고(테스트 `adminTierAndSourcePersistAndInvalidEnumsAreRejected`도 MAJOR→MINOR/DATA 저장 성공을 기대함), 그러면 `EventEngine.java:218-219`가 "서울시 상권분석서비스 … 실제 값" 출처와 "실제 데이터" 태그를 붙인다. 조건에 `area.*` DATA 변수가 없거나 문구의 `{area.X}`가 조건에 없으면 실데이터가 아닌 사건이 실측처럼 보이거나 "자료 없음"이 문구에 들어간다(EventText.java:55). REVIEW_GUIDE P1 "추정치를 실측처럼 표시"의 관리자 경로.
  제안: `source=DATA`이면 `tier=MINOR`이고, conditionExpr가 DATA 변수를 최소 하나 비교하며, 문구에 쓴 `{area.X}`(quarterLabel 제외)가 모두 조건에 있어야 저장되게 한다.

[P1] docs/balance/EVENT_BALANCE_REPORT.md — 한식(CS100001) 생존율 75.82%로 기준 41~75% 초과 (이유 기록됨)
  근거: V14 실측 표. MINOR-off 기준선 75.41% 대비 +0.41%p라 허용 오차로 처리했다는 근거가 문서에 있다. REVIEW_GUIDE상 P1이며 "이유를 남기고 다음 작업으로" 조건은 충족한다.
  제안: 메인이 허용 판단을 DESIGN 결정 기록(16장)에 한 줄 남기면 종료 가능.

[P1] backend/src/test — 새 API·에러·경계 경로 테스트 빈 곳
  근거: (1) `GET /api/missions` HTTP 응답(5개·순서·필드)과 `POST /api/games`의 잘못된 `missionCode` → 400 `VALIDATION_FAILED`를 컨트롤러 수준에서 확인하는 테스트가 없다(MissionTest는 `Mission.parse` 단위만). (2) 매각(SOLD) 경로 목표 기록 테스트 없음. (3) V14_2 라벨과 실제 value 일치(위 첫 P1)를 검사하는 테스트 없음. 프론트 목과 백엔드 응답 차이(위 목 P1)를 잡는 계약 테스트도 없다.
  제안: MockMvc 테스트 2개(missions, 잘못된 missionCode), SOLD 목표 테스트 1개, MINOR 선택지 요약·결과 칩의 수치 일치 테스트 1개를 추가한다.

---

## P2

[P2] backend/src/main/java/com/seah/startupsim/game/EventEngine.java:161-163 — `dataFires`를 계산한 뒤 바로 `history.dataFires`로 덮어씀
  근거: 161-162행 합산은 `history.dataFires`가 항상 있으므로(EventContextFactory.java:101) 사실상 죽은 코드. 단위 테스트 경로에서만 쓰인다.
  제안: 한 방식으로 통일하고 주석을 단다.

[P2] backend/src/main/java/com/seah/startupsim/game/EventEngine.java:184-190 — 가중 추첨이 부동소수 오차로 null을 반환할 수 있음
  근거: 합계와 차감을 따로 계산해 마지막 후보에서 `draw < 0`이 안 될 이론적 여지가 있다. 결정론적이라 재현성 문제는 아니고 확률도 극히 낮다.
  제안: 루프가 끝나면 마지막 후보를 반환한다.

[P2] backend/src/main/java/com/seah/startupsim/game/EventDefinitionRepository.java:182-183 — tier/source가 없으면 `raw()`를 두 번 조회
  근거: 같은 행을 두 번 읽는다.
  제안: 한 번 읽어 변수로 재사용한다.

[P2] backend/src/main/java/com/seah/startupsim/game/EventText.java:51-55 — 개수 변수도 부호를 붙여 "개업 +2곳"으로 표시됨
  근거: 계약 6.3("부호가 붙은 정수")대로지만 `openings`·`closings`는 증감이 아니라 개수라 "개업 +2곳", "폐업 +1곳"(V14_1:217, 222, 247)이 어색하다.
  제안: 증감 변수(salesChangePct, storeChange, floatingPopChangePct)만 부호를 붙이고 개수 변수는 부호 없이 쓰도록 계약과 함께 조정한다.

[P2] backend/src/main/resources/db/migration/V14_1__minor_event_content.sql (여러 줄) — "현상 유지 = 무위험 능력치 +1" 패턴
  근거: 비용 없이 능력치 +1만 주는 선택지가 많다: 62(cooking), 90, 119(LOCAL_VOUCHER 보류), 129, 134, 139, 152(stamina), 196, 201, 215, 230, 245. MINOR가 거의 매 분기 나와 한 게임에 누적될 수 있다. 270-274행에서 양수 능력치를 +1로 묶은 것은 완화책이지만 무위험 보상 자체는 남는다. 확인 필요: 기대효과 봇이 이 선택지를 얼마나 고르는지.
  제안: 현상 유지에는 보상 없음이나 작은 손해(방문객 -1% 등)를 주고, 능력치 보상은 위험이 있는 선택지에 둔다.

[P2] backend/src/main/resources/db/migration/V14_1__minor_event_content.sql:100-102, 113-115 — 보상 체인은 모든 선택지가 이득
  근거: MI_WATCH_OWNER, MI_LANDLORD_THANKS는 선행 선택의 보상이라 의도로 보이지만, 선택 자체에 고민이 없다.
  제안: 의도면 그대로 두고 지시서에 예외로 적는다.

[P2] V14_1:254-264 / docs/balance — 25% 축소 뒤 효과 범위가 지시서보다 작음
  근거: 현금 ±12.5만~125만 원, 수정치 ±0.25~2.5%로 TASK-010 1-2의 "현금 ±50~500만, 수정치 ±3~15%"보다 작다. MINOR-on/off 전체 생존율 차이도 +0.29%p라 MINOR 선택이 결과에 주는 영향이 작다. 밸런스 결정으로 문서화돼 있다.
  제안: 지시서·계약에 실제 범위를 반영하고, 체감 영향은 2단계에서 다시 본다.

[P2] backend/src/main/resources/db/migration/V14__minor_events_missions.sql:15 — `event.minor.rate`가 지시서 기본값 0.9가 아니라 1.0
  근거: EVENT_BALANCE_REPORT "2차" 조정으로 기록됨. TASK-010 1-1은 0.9.
  제안: 지시서에 변경 사실을 적는다.

[P2] backend/src/main/java/com/seah/startupsim/game/EventEngine.java:177-180 — dataFirst가 체인 후속(priority 80)보다 먼저 적용됨
  근거: DATA 후보가 하나라도 있으면 STORY를 모두 지운 뒤 priority를 비교한다. 체인 후속은 선행 뒤 2~6분기 창 안에서만 나오므로 DATA(게임당 평균 3.5회)와 MAJOR에 밀려 창을 놓칠 수 있다. 놓쳐도 `max_fires=1`이라 반복은 없고 FLAG만 남는다. 확인 필요: 실측에서 체인 완결률.
  제안: 체인 후속(priority ≥ 80)은 dataFirst보다 앞서게 하거나, 시뮬레이터에 체인 완결률을 추가한다.

[P2] backend/src/main/java/com/seah/startupsim/game/EventEngine.java:164-176 — MINOR 선택은 활성 정의 집합에 따라 달라짐
  근거: MAJOR는 정의별 스트림이라 정의를 추가해도 다른 판정이 바뀌지 않지만, MINOR는 후보 전체를 하나의 `MINOR_PICK` 난수로 가중 추첨한다. 관리자가 MINOR를 추가·비활성하면 진행 중 게임의 이후 MINOR 선택이 바뀐다. 사건 정의는 원래 버전 고정 대상이 아니므로 규칙 위반으로 보지 않는다.
  제안: 의도라면 DESIGN 결정 기록에 "MINOR 콘텐츠 변경은 진행 중 게임에 즉시 반영"을 남긴다.

[P2] backend/src/main/java/com/seah/startupsim/game/EventDsl.java (Negation) — `not` 안의 DATA 비교는 null에서 true가 됨
  근거: 비교는 null에서 false라 `{"not":{area…}}`는 null 데이터에서 참이 된다. 현재 콘텐츠는 `not`을 쓰지 않는다.
  제안: 검증기에서 DATA 변수를 `not` 아래에 두는 것을 거부한다.

[P2] backend/src/main/resources/db/migration/V14_2__minor_modifier_labels.sql:7-14, 16-19 — 반올림과 일괄 덮어쓰기
  근거: 반올림 방식 차이(rent -125,000 → "-13만 원", costRate 0.0025 → "+0.3%p"). 또 V14_1 배포 뒤 V14_2 배포 전에 관리자가 MINOR 라벨을 고쳤다면 덮어써졌다(확인 필요). 이후 관리자 편집은 라벨-값 일치를 검사하지 않는다.
  제안: 라벨을 저장값이 아니라 표시 시점에 값에서 생성하는 방향을 검토한다.

[P2] backend/src/main/java/com/seah/startupsim/game/Mission.java:9 — 코드 `AREA_TOP3`인데 의미는 5위 이내
  근거: 계약과 문서에서 의도적으로 유지했다고 적혀 있다. 읽는 사람이 헷갈린다.
  제안: 코드 주석에 "코드 이름 고정, 의미는 target=5"를 적는다.

[P2] backend/src/main/java/com/seah/startupsim/ranking/RankingRepository.java:39, web/ShareRepository.java:30 — 기록 시점 제목이 아니라 현재 enum 제목을 표시
  근거: 계약 6.4는 "기록 시점 목표를 보존"하라고 하지만 제목은 코드에서 매번 다시 만든다(이미 "동네 3위"→"5위"로 바뀐 전례가 있음). 또 `Mission.parse`는 모르는 코드면 400 예외를 던져 랭킹 전체가 실패할 수 있다(game_records에는 CHECK 제약 없음).
  제안: 제목을 기록에 저장하거나, 랭킹·공유 경로는 모르는 코드를 예외 대신 원문 코드로 보여 준다.

[P2] frontend/src/components/Rankings.tsx:54 — `missionTitle` 누락 시 '3년 버티기'로 대체
  근거: 계약 위반이나 누락을 가리고 사실이 아닐 수 있는 목표를 보여 준다.
  제안: 누락이면 "-"로 표시한다.

[P2] frontend/src/components/GameMilestones.tsx:66-67, 94-97 — 효과 칩 key 중복 가능, 100% 선택지에도 "실패 시 변화 없음" 표시
  근거: `key={e.label}`은 같은 라벨 두 개면 React 경고가 난다. MINOR 행은 성공률 100%여도 "실패 시 …"를 보여 준다.
  제안: key에 index를 섞고, 성공률 100%면 실패 문구를 숨긴다.

[P2] backend/src/main/java/com/seah/startupsim/game/GameService.java:49-52 — 시작 분기가 늦으면 SURVIVE_3Y 달성 불가 (확인 필요)
  근거: `startQuarter`를 요청으로 받을 수 있고 마지막 분기는 데이터 끝이다. 남은 분기가 12보다 적으면 12분기 목표는 원천적으로 불가능한데 생성 시 막지 않는다. 현재 UI가 시작 분기를 노출하는지는 확인하지 못했다.
  제안: 남은 분기 수가 12 미만이면 SURVIVE_3Y 선택을 거부하거나 안내한다.

[P2] backend/build.gradle:60-69 — `simulateV14` 기본 URL이 메인 DB(5433)
  근거: 테스트 코드상 메인 DB는 SELECT만 하고 쓰기는 Testcontainers에서 한다(EventBalanceSimulationTest 167-183). 비밀값은 없음.
  제안: 실수 방지를 위해 기본값 대신 명시적 지정이 필요하게 바꾸는 것을 검토한다.

---

## 요약

| 등급 | 개수 | 항목 |
| --- | ---: | --- |
| P0 | 0 | 없음 |
| P1 | 10 | 결과 칩 수치 불일치, 축소 후 문구 금액 불일치, 공짜 정답 2건, 2호점 판정 시점·매각 누락, 기존 기록 "미달성" 표시, 목 데이터 불일치, 반응·순간 DATA 토큰 미렌더, tier/source 검증 부재, 한식 생존율 초과(이유 기록됨), 테스트 빈 곳 |
| P2 | 18 | 죽은 코드·추첨 폴백·중복 조회, 개수 변수 부호, 현상 유지 +1 패턴, 보상 체인, 효과 범위 축소, rate 1.0, dataFirst와 체인, MINOR 선택의 정의 집합 의존, `not`+null, V14_2 반올림·덮어쓰기, AREA_TOP3 이름, 기록 제목 비보존·parse 예외, 랭킹 대체 문구, 칩 key·실패 문구, 시작 분기, simulateV14 기본 URL |
