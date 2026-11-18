# TASK-004B 파트 1 진행

## 1차 실행 (2026-11-18)

파트 1 전체 완료 아님. V8·시드 생성과 독립 난수 스트림까지 구현했다.

### 구현·커밋
- `814ffed`: 규범 V8 스키마 원본을 그대로 앞에 두고, CONTENT_DRAFT JSON 블록을 SQL INSERT로 자동 변환해 추가했다.
- 생성 스크립트: `pwsh -File docs/tasks/drafts-004b/generate-events.ps1`. `-Check`는 재생성 결과와 현재 V8이 일치하는지 검사한다. LF/CRLF 차이는 무시한다.
- PostgreSQL/PostGIS Testcontainers 위 Flyway V1~V8 적용 및 정의 수·선택지 수 제약 확인 테스트 추가.
- 기존 004A 골든 테스트에는 허용된 TRUNCATE 한 줄만 추가했다. 골든 JSON과 테스트 본문은 수정하지 않았다.
- `3814c1b`: ENGINE 1장 그대로 FNV-1a(UTF-8), SplitMix64 혼합, 목적 enum 9개 기반 `EventRandom` 구현. 기존 매출 난수·계산기는 수정하지 않았다.
- 기존 정의 판정을 새 정의·평가 순서 변경이 흔들지 않는지, 매출 스트림과 분리되는지, FNV 기준 벡터, 1만 회 확률 분포를 검사한다.

### 검증
- 생성기 실행 및 `-Check`: 통과. 전환점 **12**, 선택지 **28**, 반응 **36**, 순간 **12**.
- 관련 테스트: `gradlew.bat test --offline --tests '*EventSeedIntegrationTest' --tests '*EventRandomTest' --tests '*GameIntegrationTest' --tests '*QuarterCalculatorTest'` 성공, **22개 / 실패 0**.
- 004A `GameIntegrationTest.snapshot`의 고정 시드 4분기 JSON 바이트 비교 통과.
- 전체 `gradlew.bat clean check --offline`: **BUILD SUCCESSFUL**, 1분 44초, **60개 / 실패 0 / 오류 0 / 건너뜀 0**.
- compose 전용 DB 포트는 **5438** 확인. 메인 DB 5433 미사용. 마이그레이션 검증은 격리 Testcontainers DB에서 수행했다.

정의 수 확인 쿼리(시드 통합 테스트가 동일 조회를 실행하고 예상 수를 단언):
```sql
SELECT (SELECT count(*) FROM turning_points) AS turning_points,
       (SELECT count(*) FROM turning_point_options) AS options,
       (SELECT count(*) FROM reaction_templates) AS reactions,
       (SELECT count(*) FROM moment_templates) AS moments;
-- 12 | 28 | 36 | 12
```

### 다음 실행의 정확한 잔여
1. 조건·확률·효과 DSL 파서/평가기/검증기: 화이트리스트, 깊이·노드 상한, 범위, 오류 경로, 저장·로드 시 검증. 시드 전체 12+36+12 정의를 검증기로 로드하는 회귀 테스트.
2. `QuarterCalculator.Input`에 `List<Modifier>` 한 필드 추가, 빈 목록은 기존 산술 경로 보존, ADD→MULTIPLY·용량 상한·원가율 clamp·임대료 보정 및 관련 테스트. MarketDataRepository 변경 없이 AdvanceService에서 입력 보정을 연결할 방법을 적용한다.
3. EventEngine/저장소·효과 적용·전환점 발생 및 payload/rev 스냅샷. 엔딩 우선, 쿨다운, 한 분기 하나, 대기 중 advance 차단, GameState.pendingTurningPoint 연동.
4. 전환점 조회/선택 API 및 endpoint별 멱등 처리·동시 선택 직렬화. 선택은 version만 증가시키고 turn은 유지한다.
5. 반응 2~3개, 순간(STAT ±2·MODIFIER 1~2분기만, 현금 금지), 가상 경쟁점 변화와 반응 문구, 결과/집계 JSON 저장.
6. `/admin/turning-points`, `/admin/reactions`, `/admin/moments` 목록·토글·JSON 수정·검증 오류 경로·CSRF 테스트.
7. 이벤트 on 새 8분기 골든, 성공/실패 확률 경계·보정 만료·원장·shop_count·엔딩/멱등/동시성 통합 테스트 및 **8분기 데모 표**(현재 미구현).
8. 최종 전체 clean check 및 완료 조건 재검증.

MarketDataRepository와 GameService 버전 고정 코드, 다른 Flyway 버전, 파트 2는 수정하지 않았다. push 하지 않았다.

## 2차 실행 (2026-11-18)

- `62b21a8`: `EventDsl`의 sealed 조건 AST·확률 공식·효과 7종 record와 `EventDefinitionValidator` 추가.
- 조건: 8개 연산자, all/any/not/빈 객체, 결측 비교 false, 숫자·문자열·boolean 타입, 변수·키·연산자 화이트리스트, 깊이 4·노드 30·배열 20 상한. BigDecimal 비교로 원 단위 정밀도 유지.
- 확률: 기준 능력치 40, 최대 2개 statBonus, per10 범위, clamp, 정수 성공률 반올림.
- 효과: CASH/STAT/MODIFIER/FLAG/SHOP_COUNT/RIVAL_ADD/TEXT 각각 엄격한 키·타입·범위. 순간은 STAT ±2·MODIFIER 최대 2분기·TEXT만 허용.
- 전환점/반응/순간 전체 정의 검증 진입점 제공. code·선택지 수/순서·문구 길이·별점·치환 변수 검증. 위반은 400 INVALID_EXPRESSION과 JSON 경로를 포함한다.
- 실제 Flyway로 적재한 V8 콘텐츠를 DB에서 읽어 **전환점 12·반응 36·순간 12 전체 검증 성공**.
- 검증 명령: `gradlew.bat test --offline --tests '*EventDslTest' --tests '*EventSeedIntegrationTest' --tests '*GameIntegrationTest' --tests '*EventRandomTest'`.
- 결과: **BUILD SUCCESSFUL, 21개 / 실패 0 / 오류 0**, 004A 고정 시드 4분기 골든 포함. 이번 실행의 전체 clean check는 미실행(1차 60개 통과 기록과 구분).
- 실행 시간 제한에 따라 DSL 단계에서 커밋. 1차 잔여 목록의 1번 중 파서·평가기·검증기 및 시드 전수 검증은 완료. **실서비스 정의 저장·로드 호출, 부팅 전수 검사/오류 로깅·사용 시 무효 정의 스킵은 흐름·관리자 단계에서 연결해야 한다.**
- 다음 착수는 **계산기 보정**이며 1차 잔여 2~8번을 순서대로 진행한다.
- MarketDataRepository·GameService·game_data_pins·골든 파일은 이번 실행에서 수정하지 않았다. push 없음.

## 3차 실행 (2026-11-18)

### 완료
- `2e5c600`: 계산기 `Input`에 `List<Modifier>` 한 필드 추가. 기존 생성자를 빈 목록 경로로 유지하고 `withModifiers`를 제공했다. MarketDataRepository를 수정하지 않고 AdvanceService에서 입력을 감싸 활성 보정을 연결한다.
- 계산기 보정: ADD→MULTIPLY, visitors는 잘라내기/용량 상한 전에 적용, revenue는 결과에 한 번 반올림, costRate는 0.05~0.95, rent는 0 이상. 해당 target 보정이 없으면 기존 산술을 유지한다.
- 활성 보정은 `start_turn <= turn AND turn < end_turn`, `ORDER BY target, mode, id`. 실제 DB에서 시작/종료 경계·만료·정렬·다른 게임 격리를 검증했다.
- 보정 단계 검증: `ModifierApplicationTest` 6개 + 기존 계산기 6개 + 시드 통합 1개 + GameIntegrationTest 11개, **24개 통과**, 004A 골든 포함.
- `cb3fcd5`: 실서비스 `EventDefinitionRepository` 연결. 최신 활성 정의를 매번 로드·검증하고, 부팅 시 비활성 정의까지 전수 검사한다. 오류 정의는 ERROR 로그 후 해당 로드에서만 제외하며 DB enabled를 변경하지 않는다. 스냅샷용 선택지 순서/ID 조회도 제공한다.
- 정의 로더 검증: 실제 V8 전체 로드·활성 토글·잘못된 조건/치환 변수/순간 현금 효과 스킵·enabled 보존을 확인했다. 보정/DSL/난수/004A 골든 포함 **33개 통과**.
- `f1ad2df`: 엔드포인트 멱등 기반. TurnRepository에 타입 있는 공용 캐시 오버로드를 추가하고, advance도 endpoint 비교 후 역직렬화하게 변경했다. 같은 키를 choice/sell/advance 사이에 재사용하면 IDEMPOTENCY_KEY_REUSED. 실제 DB 캐시 재조회·키 재사용 거부·행 1개 테스트를 추가했다.
- 전체 `gradlew.bat clean check --offline`: **BUILD SUCCESSFUL**, 1분 54초, **71개 / 실패 0 / 오류 0 / 건너뜀 0**. 004A 고정 시드 4분기 JSON 바이트 골든 통과.

### 다음 실행의 잔여 (최신)
1. **흐름·API 본체**: EventEngine, 조건 컨텍스트/이력, 효과 적용(원장·능력치·기간 보정·플래그·매장 수·경쟁점), 발생/쿨다운/엔딩 우선, payload+rev 스냅샷, pending 상태·advance 가드, 전환점 GET/choice API·동시 선택/멱등. 새 API의 userId는 기존 GameController처럼 GuestAuth.resolve로 얻고 인증 구현은 수정하지 않는다.
2. 반응·순간·가상 경쟁점 변화, response 및 집계 JSON 저장.
3. 관리자 3화면·검증 저장·CSRF·토글·대기 스냅샷 불변 테스트.
4. 이벤트 on 8분기 골든과 데모 표, 흐름·확률 경계·원장·shop_count·엔딩·멱등/동시성 최종 검증.
5. 최종 전체 검사 및 완료 조건 재확인.

정의 **로드/부팅 검증 연결은 이번에 완료**했다. 관리자 저장 검증 연결은 잔여다. MarketDataRepository·GameService·game_data_pins·GuestAuth·보안 필터·기존 골든 파일은 이번 실행에서 수정하지 않았다. push 없음.

## 4차 실행 (2026-11-18, Claude가 Codex 진행분 인계)

### main 병합
- 진행 중 변경을 `git stash -u` → `git merge main`(TASK-006A 회원 인증, V7_2, `.gitattributes`) → `git stash pop`. 충돌 없음(`99f07ad`).
- 전환점 API 사용자 식별은 기존 GameController와 같이 `GuestAuth.resolve`를 쓴다. 병합 후 `GuestAuth`가 보안 컨텍스트(Bearer)와 `X-Guest-Token`을 모두 받으므로 HTTP 테스트에서 두 헤더를 모두 확인했다.

### Codex 미커밋 변경 처리
- `AdvanceService`(대기 중 409 `TURNING_POINT_PENDING`), `GameRepository.pendingTurningPoint`, `EventContextFactory`, `EventEffectService`, `TurningPointService/Controller`, `TurningPointIntegrationTest`는 그대로 살리고 완성했다(테스트 3개가 병합 후에도 통과해 버릴 이유 없음).
- `EventEngine`은 다시 썼다. 이유: 전환점만 다뤘고 순간·반응·경쟁점이 없었으며, 엔딩 분기에서 반응·순간까지 건너뛰었다. 새 구조는 순간 → 가상 경쟁점 → 반응 → (엔딩이 아니면) 전환점. 정의별 발생 판정을 `judge()`로 분리해 정의 추가 불변 테스트가 직접 검사한다.
- 효과 요약 문구를 계약 형식으로 바꿨다(`지원금 +1,000원`, `매출 +20%`, `+4%p`). 플래그는 내부 상태라 화면 요약에서 뺐다.

### 구현·커밋
- `a6f32ce` 이벤트 엔진 흐름·전환점 GET(200/204)/POST choice. choice는 `Idempotency-Key` 필수, `endpoint='turning-point-choice'`, 다른 엔드포인트 키 재사용 409 `IDEMPOTENCY_KEY_REUSED`, 게임 행 잠금으로 직렬화, `games.version`만 +1(`current_turn` 유지), 이미 해결 409 `TURNING_POINT_ALREADY_RESOLVED`, 대기 없음 404 `NO_PENDING_TURNING_POINT`, 남의 선택지 400. 대기 행은 payload(제목·설명·선택지·성공률·효과 요약·효과 원본)와 `definition_rev`를 스냅샷하고 선택 시 정의 테이블을 다시 읽지 않는다.
  - `NeighborhoodEventService`: 순간(가중 추첨, `0.5+(stat-difficulty)×slope` clamp, STAT/MODIFIER/TEXT만, 현금 없음), 반응(2~3개, 첫 뽑기는 REVIEW 풀, `{shopName}{areaName}{serviceName}`만 치환, likes는 `REACTION_LIKES` 스트림), 가상 경쟁점(개업·폐업 비율, 실제 점포 행은 닫지 않음).
  - 가상 경쟁점 변화는 별도 필드 없이 반응 한 자리를 "동네 커뮤니티" 문구로 쓴다(총 개수는 그대로 2~3). 반응 템플릿이 모두 꺼져 있으면 경쟁점 변화도 멈춘다(노출 경로가 없으므로).
  - `turn_results.reactions`·`moment`(V8 사본 컬럼)와 `response`를 같은 값으로 저장. 활성 정의가 0건이면 엔진이 즉시 반환한다.
- `c749064` 이벤트 켠 8분기 새 골든 `src/test/resources/game/events-on-eight-quarters.json`(V8 시드 콘텐츠 그대로, 시드 20261118, 선택 규칙 = 분기 번호 mod 선택지 수). 같은 시드 두 게임의 바이트 동일 + 골든 일치. 골든 재생성은 `WRITE_EVENT_GOLDEN=1` 환경 변수로만.
  - 정의 추가 불변: V8 전환점 12개 vs 새 정의(priority 1000, p=0.9) 추가 + 역순 정렬, 시드 20개 × 분기 8개에서 기존 12개 판정이 모두 같음.
  - 확률 경계: 성공률 10%/90% 선택지를 고정 시드(첫 난수가 0.1 이상·0.9 미만인 시드)로 실패/성공 각각 확인, 실패 시 현금 불변.
  - HTTP: 204/401/200(Bearer), 키 없음 400, 잘못된 optionId 400, 대기 중 advance 409, 선택 200, 다른 키 재선택 409.
- `bfe876f` 관리자 `/admin/turning-points`, `/admin/reactions`, `/admin/moments`: 목록(코드·이름·활성·rev·전환점은 최근 30일 발생 수), `POST …/{id}/toggle`(enabled 반전 + rev+1, PRG), `GET/POST …/{id}` JSON 편집. 저장 시 부팅·사용 시와 같은 `EventDefinitionValidator`를 통과해야 하고 실패하면 400과 오류 경로(`conditionExpr…`, `baseProbability…`)를 폼에 표시하고 저장하지 않는다. code 변경 거부, 삭제 없음, 기존 보안·CSRF 그대로.
  - 대기 스냅샷 불변: 대기 중 관리자 저장(제목·효과 변경)과 비활성화 뒤에도 GET 응답이 같고, 선택은 스냅샷 효과(+1,000원)로 적용, `definition_rev`=1 유지.

### 8분기 데모 (고정 시드 20261118, 이벤트 켬, `EventFlowIntegrationTest` 실행 결과 `build/reports/events-demo.md`)

| 분기 | 순이익 | 전환점 | 선택 | 결과 | 반응 수 | 순간 |
|---|---:|---|---|---|---:|---|
| 20231 | 21,359,778 | - | - | - | 2 | 옆 가게와 식자재·비품 공동 구매 (실패) |
| 20232 | 37,324,773 | - | - | - | 2 | 계절 한정 메뉴 시식회 (실패) |
| 20233 | 33,000,302 | DELIVERY_APP | 배달 플랫폼 입점 | 성공 | 2 | 새벽 시장 직거래 발품 (실패) |
| 20234 | 52,370,024 | RENT_HIKE | 인상안 수용 | 성공 | 2 | 단골손님의 친구 동반 방문 (실패) |
| 20241 | 59,078,193 | REDEVELOPMENT_RUMOR | 권리금 받고 인근으로 이전 | 성공 | 2 | 새벽 시장 직거래 발품 (성공) |
| 20242 | 51,779,917 | SECOND_BRANCH | 내실을 다지며 보류 | 성공 | 2 | 까다로운 미식가의 방문 (실패) |
| 20243 | 55,171,675 | - | - | - | 3 | 단골손님의 친구 동반 방문 (성공) |
| 20244 | 79,945,116 | CORPORATE_CATERING | 단발성 체험 납품으로 간 보기 | 성공 | 2 | 재고 로스 제로 달성 (성공) |

픽스처는 테스트용 가상 상권 1개(9분기 데이터)다. 실제 상권 데이터 기반 수치가 아니다. 관찰: 8분기 중 전환점 5회로 빈도가 높고 선택 결과가 모두 성공이다(선택 규칙상 성공률 100% 선택지가 많이 걸림). 밸런스 조정은 관리자 화면 몫으로 남긴다.

### 검증
- `gradlew.bat clean check --offline`: **BUILD SUCCESSFUL**, 2분 30초, **106개 / 실패 0 / 오류 0 / 건너뜀 0**. 004A `GameIntegrationTest` "고정 시드 4분기 JSON 회귀 스냅샷" 통과(골든 파일 수정 없음).
- worktree DB 5438에 `bootRun`(앱 8086)으로 V1~V8(V7_2 포함) 순서 적용 확인, 부팅 정의 검증 ERROR 로그 없음. 정의 수 쿼리 결과:
  ```
   turning_points | options | reactions | moments
               12 |      28 |        36 |      12
  ```
  같은 앱에서 `GET /api/games/1/turning-point` 토큰 없음 401, `/admin/turning-points` 비로그인 302 확인 후 앱 종료.
- MarketDataRepository, GameService 창업·버전 고정, 004A 골든 파일, 인증·보안 필터는 수정하지 않았다. push 없음.

### 남은 것·제안
- 파트 1 완료 조건 1~3 충족. 파트 2(V9)는 미착수.
- 제안: API_CONTRACT 3장에 `NO_PENDING_TURNING_POINT`(404), `TURNING_POINT_ALREADY_RESOLVED`(409) 코드를 명시하면 프론트 처리 분기가 분명해진다(ENGINE 3.3에는 있음).
- 제안: 관리자 이벤트 목록 경로는 지시서대로 `/admin/turning-points` 등을 썼다(ENGINE 6장의 `/admin/events/...`와 다름, 지시서 우선).
