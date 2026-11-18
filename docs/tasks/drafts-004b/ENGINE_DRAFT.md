# TASK-004B 이벤트 엔진 설계 초안 (Claude, 2026-11-18)

대상: 파트 1(V8) — 전환점 이벤트, 동네 반응, 이번 분기의 순간.
기준 문서: `docs/DESIGN.md` 4~8·11·16장, `docs/api/API_CONTRACT.md` 3장, `docs/tasks/TASK-004A.md`, `AGENTS.md`.
읽은 코드(main c7368d4): `game/AdvanceService.java`, `QuarterCalculator.java`, `GameTypes.java`, `GameRepository.java`, `GameService.java`, `V5__game.sql`, `V3__area_quarterly.sql`, `V2__trade_areas.sql`, `V7__catalog.sql`, `GameIntegrationTest.java`.
SQL 초안: 같은 폴더 `V8__events.sql`. 콘텐츠(전환점 12개·반응·순간)는 Gemini `CONTENT_DRAFT.md`.
전제: **TASK-004A 병합 뒤** 그 확장 지점(`TurnEventHook`, 타입 도메인, `pendingTurningPoint`) 위에 올린다.

반영한 메인(파트 2) 결정 — 2026-11-18:
- MVP 엔딩은 CLOSED / LONG_RUNNING / SOLD 3개. FRANCHISE_HQ는 후속 → **엔딩 플래그 효과는 MVP 미사용**(플래그는 "이후 전환점 조건용 상태"로만 남긴다).
- 매장 수는 `games.shop_count int NOT NULL DEFAULT 1 CHECK (BETWEEN 1 AND 5)`를 V8에 추가. 2호점은 `shops` 행을 늘리지 않고 수치 보정으로 표현. 효과 키 `SHOP_COUNT` 허용.
- 엔딩 판정은 지금처럼 advance 트랜잭션 안. **엔딩이 난 분기에는 전환점을 만들지 않는다**(엔딩 우선).
- 매각 가치 계산은 파트 2 소유. 전환점 효과가 끼어들지 않는다.

추가 반영 — 메인 결정 2026-11-18 (2차, 아래 Q1~Q7 답):
- Q1 **확정**: 전환점 선택 응답의 `game.version`을 다음 요청(`advance`, `sell`)의 `expectedVersion`으로 쓴다. API 계약에 명시한다.
- Q2 **유지**: 전환점 알림은 `GameState.pendingTurningPoint`로만. `QuarterResult`에 필드를 추가하지 않는다.
- Q3 **변경**: 순간(moment)은 **즉시 현금 효과를 두지 않는다.** 능력치 소폭 변화(`STAT`) 또는 다음 분기부터의 보정(`MODIFIER`)만 허용 → 결산 카드 숫자와 잔액이 어긋나는 문제를 구조적으로 없앤다.
- Q4 **확정**: 전환점 대기 중 `POST /sell`도 `409 TURNING_POINT_PENDING`(advance와 동일). advance 경로는 엔딩 우선이라 대기 행이 생기지 않는다. `game_records`에 미해결 전환점 표시 불필요.
- Q5: DESIGN 11·16장 갱신은 메인이 한다. Q6 승인. Q7 골든 테스트 TRUNCATE 조건은 지시서에 들어간다. `idempotency_keys.endpoint` 승인(`sell`은 `'sell'`).

---

## 1. 난수 스트림 설계

### 1.1 지금 상태
`QuarterCalculator.calculate()`는 난수를 딱 한 번 쓴다.

```java
double random = new SplittableRandom(i.seed() ^ ((long) i.turn() * 0x9E3779B97F4A7C15L)).nextDouble(.9, 1.1);
```

즉 "매출 변동 스트림 = `seed ^ (turn × GOLDEN)`" 하나뿐이다. 여기에 이벤트 판정을 끼워 넣으면 분기 결산이 바뀌므로, 이벤트는 **다른 시드에서 파생된 별도 인스턴스**를 쓴다.

### 1.2 파생 규칙

```java
/** 목적과 정의 code별로 독립적인 난수 스트림. 매출 스트림과 겹치지 않는다. */
final class EventRandom {
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    /** FNV-1a 64비트. JVM 구현에 의존하지 않도록 String.hashCode()를 쓰지 않는다. */
    static long hash64(String text) {
        long h = 0xCBF29CE484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h = (h ^ (b & 0xFF)) * 0x100000001B3L;
        }
        return h;
    }

    /** SplitMix64 최종 혼합: 비슷한 입력도 서로 상관 없는 스트림이 된다. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static SplittableRandom stream(long seed, int turn, Purpose purpose, String key) {
        long purposeHash = hash64(purpose.name() + '|' + key);   // key는 정의 code 또는 ""
        return new SplittableRandom(mix64(seed ^ (turn * GOLDEN) ^ purposeHash));
    }
}
```

목적(`Purpose`)은 고정 enum이다.

| Purpose | key | 쓰는 곳 | 뽑는 값 |
| --- | --- | --- | --- |
| `TURNING_POINT_FIRE` | 전환점 code | 전환점별 발생 판정 | `nextDouble()` 1회 |
| `TURNING_POINT_PICK` | `""` | 후보 2개 이상일 때 최종 1개 선택 | `nextDouble()` 1회 |
| `TURNING_POINT_SUCCESS` | `전환점 code + "#" + order_no` | 선택지 성공/실패 | `nextDouble()` 1회 |
| `REACTION_PICK` | `""` | 반응 2~3개 가중 추첨 | 뽑는 개수만큼 |
| `REACTION_LIKES` | 반응 template code | likes 숫자 | 1회 |
| `MOMENT_PICK` | `""` | 순간 템플릿 1개 선택 | 1회 |
| `MOMENT_JUDGE` | 순간 template code | 성공 판정 | 1회 |
| `VIRTUAL_RIVAL` | `""` | 가상 경쟁점 등장 수 | 1~2회 |
| `RIVAL_CLOSURE` | `""` | 경쟁점 폐업 | 1회 |

### 1.3 정의 추가·순서 변경에 둔감하게 만드는 장치

1. **판정별 스트림을 정의 code로 분리**한다. 전환점 A의 발생 판정은 `("TURNING_POINT_FIRE", "A")` 스트림의 첫 난수만 쓴다. 그러므로 전환점 B를 새로 추가해도, B를 비활성화해도, 평가 순서를 바꿔도 **A의 판정 결과는 변하지 않는다**. (지금 흔한 실패 패턴인 "하나의 RNG를 루프에서 공유"를 쓰지 않는다.)
2. **id가 아니라 code를 해시**한다. bigserial id는 삽입 순서·재삽입에 따라 바뀌므로 재현성 기준으로 쓸 수 없다.
3. **후보 정렬은 결정론적으로**: `ORDER BY priority DESC, code ASC`. DB 반환 순서나 id에 의존하지 않는다.
4. **가중 추첨도 정렬 후**: 후보를 `code ASC`로 정렬하고 누적 가중치로 뽑는다. 템플릿을 추가하면 그 분기의 반응 선택은 바뀔 수 있다(가중 공간이 변하므로 불가피). 그래서 **반응·순간은 "콘텐츠 데이터 버전"이 결과에 영향을 준다**는 점을 명시한다 → 1.4의 재현성 정의에 포함.
5. 같은 분기에 같은 목적·같은 key의 난수를 두 번 뽑지 않는다(멱등 재실행 시 값이 흔들리지 않게).

### 1.4 004A 바이트 재현성 테스트를 깨지 않는 조건

004A가 남길 "고정 시드 4분기 결과 JSON 바이트 비교" 테스트는 아래 조건을 전부 지키면 그대로 통과한다.

1. **매출 난수 호출을 건드리지 않는다.** `QuarterCalculator`는 기존 `new SplittableRandom(seed ^ (turn*GOLDEN)).nextDouble(.9,1.1)`을 그대로 유지하고, 이벤트 난수는 별도 `SplittableRandom` 인스턴스로 만든다. `split()`로 파생하지 않는다(파생하면 부모 상태가 변해 매출 값이 바뀐다).
2. **보정이 없으면 산술을 건너뛴다.** 활성 modifier 목록이 비어 있으면 `× 1.0`, `+ 0`도 하지 않는다(부동소수 반올림·`Math.round` 경계에서 값이 흔들릴 수 있다). 코드로는 `if (modifiers.isEmpty()) return base;` 형태.
3. **JSON 모양을 바꾸지 않는다.** `GameTypes.Result`의 필드 순서와 이름을 유지하고, `moment`는 이벤트가 없으면 `null`, `reactions`는 `[]`를 유지한다(지금 값과 동일). 필드를 추가하지 않는다. 새 정보(예: 적용된 보정 목록)가 필요하면 `Result`가 아니라 전환점 선택 응답이나 `GameState`에 둔다.
4. **이벤트 테이블이 비어 있으면 아무 일도 일어나지 않는다.** `TurnEventHook` 구현은 활성 정의가 0건이면 즉시 반환한다. 그래서 V8 **스키마만** 적용된 상태에서는 결과 바이트가 동일하다.
5. **콘텐츠 INSERT는 재현성 테스트 픽스처에서 제외한다.** 콘텐츠(전환점 12개, 반응 템플릿)가 V8에 INSERT로 들어가면 테스트 DB에도 들어가 반응이 붙는다. 그러므로 004A 골든 파일 테스트의 `@BeforeEach`에 다음을 추가한다.

   ```sql
   TRUNCATE turning_points, reaction_templates, moment_templates CASCADE;
   ```

   이 한 줄이 "이벤트가 하나도 발생하지 않는 경로"를 보장한다(BRIEF 원칙 1). 그리고 **이벤트가 켜진 상태의 재현성은 별도 골든 파일로 새로 기록**한다(2.x 참고: `events-on` 4분기 골든). 같은 시드·같은 선택·같은 콘텐츠 리비전이면 바이트 동일해야 한다.
6. 재현성의 정의를 문서에 확장한다: **시드 + 플레이어 선택 + 참조 데이터 버전 + 이벤트 콘텐츠 리비전**이 같으면 결과가 같다. 관리자가 전환점을 수정하면(=리비전 변경) 과거 골든 파일과는 달라질 수 있다 → 그래서 골든 파일 테스트는 자기 픽스처가 심은 콘텐츠만 쓴다.

---

## 2. 조건·효과 DSL (화이트리스트 JSON)

SpEL·스크립트·임의 표현식 금지. 파서는 노드 3종만 안다.

### 2.1 조건식 문법

```json
{"var": "<화이트리스트 변수>", "op": "<연산자>", "value": <리터럴>}
{"all": [노드, ...]}
{"any": [노드, ...]}
{"not": 노드}
{}                     // 항상 참
```

- 연산자: `==`, `!=`, `>`, `>=`, `<`, `<=`, `in`(value는 배열), `between`(value는 `[min,max]`, 양끝 포함)
- 타입 검사: 숫자 변수에 문자열 비교를 쓰면 저장 거부. boolean 변수는 `==`/`!=`만.
- 한도: 중첩 깊이 ≤ 4, 노드 수 ≤ 30, 배열 원소 ≤ 20.
- **알 수 없는 키·변수·연산자는 저장 시 거부**(`400 INVALID_EXPRESSION`, 메시지에 경로 표시: `conditionExpr.all[1].var`). 관리자 저장, Flyway 콘텐츠 INSERT 후 부팅 검증(`EventDefinitionValidator`가 `ApplicationReadyEvent`에서 전체 재검증 → 위반 시 로그 ERROR + 해당 정의 자동 비활성 아님, 사용 시점에 스킵) 두 군데서 검사한다.
- 평가 중 값이 없는 변수(계산 불가, 예: 4분기 전 매출 없음)는 **비교 결과 false**. `not`으로 뒤집으면 true가 되므로, 콘텐츠 작성 시 결측 의존 조건은 다른 조건과 묶도록 Gemini에 안내했다.

### 2.2 변수 화이트리스트 (실제로 계산 가능한 것만)

평가 시점은 "방금 끝낸 분기의 결산 직후"(advance 트랜잭션 안)다. `turn`은 방금 계산한 분기.

| 변수 | 타입 | 근거(코드/테이블) |
| --- | --- | --- |
| `stat.cooking` `stat.service` `stat.management` `stat.marketing` `stat.stamina` `stat.network` | int 0~100 | `owners` 컬럼, `GameRepository.stats()`. 능력치 변화 적용 **후** 값 |
| `quartersOperated` | int | `games.current_turn`(advance 증가 전 turn + 1) |
| `negativeQuarters` | int | `games.negative_quarters` (이번 분기 갱신 후) |
| `quarterOfYear` | int 1~4 | `GameRepository.quarter(start,turn)` 끝 한 자리 |
| `cash` | long | 이번 분기 `result.cashAfter` |
| `cumulativeProfit` | long | `SELECT coalesce(sum(profit),0) FROM turn_results WHERE game_id=?` |
| `lastProfit` / `lastRevenue` | long | 이번 분기 `turn_results.profit` / `revenue` |
| `areaRank` | int | `turn_results.area_rank` (= `QuarterCalculator` areaRank.rank) |
| `areaRankTotal` | int | `result.areaRank().get("of")` = `store_count + 1` |
| `areaRankPct` | double 0~1 | `areaRank / areaRankTotal` (작을수록 상위) |
| `staff` `openHours` `marketingBudget` | int/long | `turn_decisions` 이번 분기 행 |
| `priceLevel` | enum `LOW|NORMAL|HIGH` | `turn_decisions.price_level` |
| `industryCategory` | enum `FOOD|SERVICE|RETAIL` | `service_industries.category` (V2, CHECK 제약 존재) |
| `serviceCode` | string | `shops.service_code` |
| `shopCount` | int 1~5 | `games.shop_count` (V8 신규) |
| `rivalCount` | int | `SELECT count(*) FROM rivals WHERE game_id=? AND closed_turn IS NULL` |
| `area.storeCount` `area.franchiseCount` `area.openingCount` `area.closingCount` | long | `area_stores_q` (V3 컬럼 `store_count, franchise_count, opening_count, closing_count`), 게임의 `game_data_pins` 버전으로 고정 |
| `area.openingRate` `area.closingRate` | long | `area_stores_q.opening_rate / closing_rate` 원자료. **단위 미확인**(DATA_NOTES) → 절대 임계값보다 비교·추세 사용 권장 |
| `area.closingRateDelta` | long | 이번 분기 `closing_rate` − 직전 분기 값. 직전 분기 행이 없으면 **결측(false)** |
| `area.salesTrendPct` | double | `area_sales_q.sales_amount` 이번 분기 ÷ 4분기 전 − 1. 어느 쪽이든 없거나 0이면 결측 |
| `area.areaType` | string | `trade_areas.area_type_name` (V2) |
| `history.timesFired.<CODE>` | int | `game_turning_points` 카운트 |
| `history.quartersSince.<CODE>` | int | 마지막 발생 turn과의 차. 없으면 999 |
| `flag.<FLAG_CODE>` | boolean | `game_flags` (V8 신규) |
| **반응 전용** `bucket.profit` | `UP|FLAT|DOWN|LOSS` | 이번 profit vs 직전 profit: 음수면 `LOSS`, ≥ +10% `UP`, ≤ −10% `DOWN`, 그 외 `FLAT` |
| **반응 전용** `bucket.rank` | `UP|SAME|DOWN` | `area_rank` 직전 분기 대비 |
| **반응 전용** `bucket.visitors` | `UP|FLAT|DOWN` | visitors ±10% |
| **반응 전용** `bucket.price` | `LOW|NORMAL|HIGH` | 결정값 그대로 |
| **반응 전용** `bucket.hours` | `SHORT|NORMAL|LONG` | openHours ≤ 8 / 9~12 / ≥ 13 |
| **반응 전용** `bucket.firstQuarter` | boolean | `turn == 0` |
| **반응 전용** `lastResolved.code` | string | 마지막으로 **해결된** `game_turning_points` 정의 code, 없으면 `"NONE"` |
| **반응 전용** `lastResolved.result` | `SUCCESS|FAIL|NONE` | 같은 행의 `success` |
| **반응 전용** `lastResolved.quartersSince` | int | 해결 분기와의 차, 없으면 999 |

**제외한 것과 이유**
- 유동인구·직장인구(`area_floating_pop_q`, `area_worker_pop_q`): 단위 미확인(V3 COMMENT, DATA_NOTES). 임계값 조건이 의미를 못 가진다.
- 임대 시세: 실측 데이터 없음(DESIGN 8장) → `rent.<자치구>` 설정값뿐이므로 조건 변수로 노출하지 않는다.
- 실제 점포 상호(`stores.name`): AGENTS.md 절대 규칙 6에 따라 이벤트 조건·문구에 쓰지 않는다.
- `share`(점유율): `turn_results.share`로 계산 가능하지만 추정 매출 결측 경로에서 의미가 흔들려 v1 제외. 필요하면 v1.2에서 추가.

### 2.3 확률 공식

```json
{"base": 0.35, "statBonus": [{"stat": "network", "per10": 0.06}], "min": 0.05, "max": 0.85}
```

```
p = base + Σ((stat - 40) / 10.0 × per10)
p = clamp(p, min, max)
```

- 기준점 40은 사장님 후보 평균대(총합 240 / 6종)다. `OwnerCandidates`와 `QuarterCalculator`도 40을 기준으로 쓰므로 일관된다.
- 검증 범위: `base, min, max ∈ [0,1]`, `max ≥ min`, `per10 ∈ [-0.2, 0.2]`, `statBonus` 최대 2개, stat은 6종 화이트리스트.
- 전환점 발생은 `turning_points.base_probability + stat_bonus`, 선택지 성공은 `turning_point_options.success_formula`가 같은 형식을 쓴다.
- 화면에 내려주는 `successRatePct = (int) Math.round(p * 100)`. 클라이언트는 확률 계산에 참여하지 않는다(서버 판정, DESIGN 16장).
- 순간 판정은 별도 공식: `p = 0.5 + (stat − difficulty) × event.moment.statSlope(0.02)`, `[0.05, 0.95]` clamp.

### 2.4 효과(effect) 화이트리스트

배열이고, 원소는 `type`으로 구분한다. 적용 순서 = 배열 순서(결정론).

| type | 키 | 검증 | 적용 |
| --- | --- | --- | --- |
| `CASH` | `amount`(원, 음수 가능), `memo` | abs ≤ 5억 | `games.cash += amount`, `ledger_entries(type='EVENT', memo)` 1행 |
| `STAT` | `stat`, `delta` | delta −10~10 | `owners.<stat> = clamp(0,100)` |
| `MODIFIER` | `target`, `mode`, `value`, `quarters`, `label` | target ∈ `revenue|costRate|rent|visitors`, mode ∈ `MULTIPLY|ADD`, MULTIPLY value 0.1~3.0, quarters 1~8, label ≤ 40자 | `game_modifiers` 1행 삽입 (`start_turn = 다음 분기`, `end_turn = start + quarters`) |
| `FLAG` | `flag`, `value` | `^[A-Z][A-Z0-9_]{0,39}$` | `game_flags` upsert / 삭제. **MVP 엔딩 판정에는 쓰지 않는다**(메인 결정 1) |
| `SHOP_COUNT` | `delta` | `delta ∈ {1,-1}` | `games.shop_count = clamp(1,5)` |
| `RIVAL_ADD` | `count`, `franchise` | count 1~3 | `rivals(is_virtual=true, service_code=내 업종, geom=내 가게 좌표 주변, is_franchise, opened_turn=다음 turn)` |
| `TEXT` | `text` | ≤ 300자 | 숫자 변화 없이 결과 문구만 |

순간(`moment_templates.on_success/on_fail`)은 **더 좁은 부분집합**: `STAT`(delta ±2), `MODIFIER`(quarters 1~2), `TEXT`. **`CASH`는 순간에서 금지**(메인 결정 Q3: 결산 카드 숫자와 잔액 불일치를 없애기 위해 즉시 현금 효과를 두지 않는다). `FLAG`, `SHOP_COUNT`, `RIVAL_ADD`도 순간에서 금지.

금지(저장 거부): 게임 종료·엔딩 확정, `games.version`/`status` 직접 변경, 참조 데이터 변경, 다른 게임 참조, SQL·표현식 문자열.

### 2.5 계산기에 반영하는 방법 — `modifier` 테이블 + `Input` 확장 (둘 다, 역할 분리)

선택: **상태는 `game_modifiers` 테이블에 두고, `QuarterCalculator`는 순수 함수로 남긴다.** `QuarterCalculator.Input`에 `List<Modifier> modifiers`(record `Modifier(String target, String mode, double value)`) 한 필드만 추가한다.

이유: 계산기를 DB에서 떼어 놓은 지금 구조(004A가 더 강화)를 유지해야 단위 테스트가 쉽고, 지속 분기 수 관리는 SQL(`start_turn <= turn AND turn < end_turn`)이 가장 단순하다.

읽기 쿼리(고정 순서):

```sql
SELECT target, mode, value
  FROM game_modifiers
 WHERE game_id = ? AND start_turn <= ? AND ? < end_turn
 ORDER BY target, mode, id
```

적용 지점(기존 식에 끼워 넣는 위치를 명시):

```
visitors = min(capacity, base_visitors)                 -- 기존
  → visitors 보정은 base_visitors에 먼저 적용하고 그 뒤 capacity로 자른다
     (처리량 상한이 여전히 구속력을 갖게)
revenue  = visitors × ticket                            -- 기존
  → revenue 보정은 이 곱 결과에 적용 (Math.round 한 번)
costRate = cost.FOOD × (1 - (management-40)*0.002)      -- 기존
  → costRate 보정(ADD는 %p, MULTIPLY는 배율) 적용 후 clamp(0.05, 0.95)
rent     = settings rent.<자치구>                        -- 기존
  → rent 보정 적용 후 max(0, ...)
```

각 target에서 `ADD`를 먼저 모두 더하고 그다음 `MULTIPLY`를 모두 곱한다(위 `ORDER BY`가 `ADD < MULTIPLY` 사전순이라 쿼리 순서와 일치). **보정 목록이 비면 어떤 연산도 하지 않는다**(1.4 조건 2).

`shop_count`는 보정이 아니라 상태다. v1에서 매출식에 직접 넣지 않고, 2호점 전환점이 `SHOP_COUNT + MODIFIER(revenue/rent)`를 함께 적용해 표현한다(메인 결정 2). 랭킹 동점 처리에서 파트 2가 `games.shop_count`를 읽는다.

---

## 3. 흐름

### 3.1 advance 안에서의 순서

004A의 `TurnEventHook.afterQuarter(ctx, outcome, resultBuilder)`를 `EventEngine`이 구현한다. 호출 위치는 **결산 저장 뒤, 엔딩 판정 뒤, 응답 조립 전**(엔딩 우선, 메인 결정 3).

```
advance(gameId, key, expectedVersion)
 1. games FOR UPDATE                                   (기존)
 2. idempotency_keys 조회 (endpoint='advance')          (기존 + endpoint 비교)
 3. version 검사 → 409 GAME_VERSION_CONFLICT            (기존)
 3b. 대기 중 전환점 있으면 → 409 TURNING_POINT_PENDING   (신규)
 4. 결정·시장 데이터 로드, 활성 modifier 로드            (신규: modifier)
 5. QuarterCalculator.calculate()                      (기존)
 6. turn_results / ledger_entries / owners 저장          (기존)
 7. 엔딩 판정 (CLOSED, LONG_RUNNING)                     (기존)
 8. EventEngine.afterQuarter():                        (신규)
      a. 순간 판정   → moment, 작은 효과 적용
      b. 반응 선택   → reactions 2~3개
      c. 경쟁점 변화 (가상 경쟁점 등장/폐업)
      d. 엔딩이 안 났으면 전환점 발생 판정 → 대기 행 생성
 9. games 갱신 (current_turn+1, version+1, cash, shop_count, status)
10. 응답 = {result, game} → idempotency_keys 저장        (기존)
```

순서 근거: 반응이 "이번 분기 결과"를 말하므로 결산 이후여야 하고, 전환점은 분기가 끝난 뒤 제시되므로 마지막. 순간의 효과는 능력치 변화와 다음 분기 보정뿐이고 **현금을 건드리지 않으므로**(메인 결정 Q3) `result.profit`/`result.cashAfter`와 `games.cash`는 항상 일치한다. 능력치 변화는 결산 카드의 `statChanges`가 아니라 순간 문구로 전달되고, 다음 분기 `GameState.owner.stats`에서 보인다.

전환점 효과의 `CASH`는 분기 결산이 아닌 별도 사건이므로 `games.cash`에 더하고 `ledger_entries(type='EVENT')`에 남긴다. 선택 응답의 `game.cash`가 그 반영 후 값이다.

### 3.2 전환점 발생 판정

```
후보 = SELECT * FROM turning_points
       WHERE enabled AND min_turn <= :turn
       ORDER BY priority DESC, code
       (각 code별로 아래를 순서 무관하게 평가)
조건: condition_expr 평가 == true
      AND history.timesFired.code < max_fires
      AND history.quartersSince.code >= cooldown_quarters
      AND 직전 분기에 전환점이 해결되지 않았다면? (아니오, 쿨다운은 정의별로만)
발생: EventRandom.stream(seed, turn, TURNING_POINT_FIRE, code).nextDouble() < p(code)
```

- 발생한 후보가 여러 개면 `priority DESC, code ASC`에서 첫 번째를 택한다. 동일 priority가 여럿이면 `TURNING_POINT_PICK` 스트림으로 균등 선택(코드 정렬 후 인덱스) — 콘텐츠가 priority를 다르게 주면 이 경로는 안 쓴다.
- **한 분기 최대 1개**: 선택 로직 + `UNIQUE(game_id, turn)` + 부분 유니크 인덱스(게임당 미해결 1개)로 DB에서도 막는다.
- **전역 쿨다운**: `event.fire.minTurn`(기본 1)으로 첫 결산(turn 0)에는 띄우지 않는다. 정의별 쿨다운은 `cooldown_quarters`, 재발생 상한은 `max_fires`(기본 1 = 게임당 한 번).
- 대기 행 생성 시 `definition_rev`와 렌더된 `payload`를 스냅샷한다(6장).

### 3.3 조회·선택 API (계약 3장 그대로)

- `GET /api/games/{gameId}/turning-point`
  - 미해결 행이 있으면 `200`에 그 행의 `payload`를 그대로 반환(`gameTurningPointId`, `code`, `title`, `description`, `options[{optionId,label,successRatePct,onSuccess,onFail}]`). 없으면 `204`.
  - `onSuccess`/`onFail` 문자열은 효과 배열의 `label`을 `, `로 이은 요약이다(`"매출 +20%, 원가율 +5%p"`). 효과 원본 JSON은 클라이언트에 내리지 않는다.
- `POST /api/games/{gameId}/turning-point/choice` body `{"optionId": 1}`, 헤더 `Idempotency-Key`
  ```
  1. games FOR UPDATE                       (advance와 같은 잠금 → 두 흐름이 직렬화)
  2. idempotency_keys 조회: (game_id, key)
       - 있고 endpoint='turning-point-choice' → 저장된 응답 그대로 200
       - 있고 endpoint 다름 → 409 IDEMPOTENCY_KEY_REUSED
  3. 미해결 game_turning_points 행 조회 (FOR UPDATE)
       - 없으면 404 NO_PENDING_TURNING_POINT
  4. optionId가 그 행의 payload 선택지에 속하는지 검사 → 아니면 400
  5. 성공 판정: EventRandom.stream(seed, 발생 turn, TURNING_POINT_SUCCESS, code+"#"+order_no).nextDouble() < p
  6. 효과 적용 (CASH/STAT/MODIFIER/FLAG/SHOP_COUNT/RIVAL_ADD), ledger 기록
  7. 행 갱신: chosen_option_id, success, applied_effects, result_text, resolved_at=now()
  8. games.version += 1   (current_turn은 건드리지 않는다)
  9. 응답 {success, resultText, effects[{label,value}], game: state(...)} → idempotency_keys(endpoint='turning-point-choice') 저장
  ```
  - `expectedVersion`은 계약에 없다. 대신 "미해결 행이 존재하고 아직 `chosen_option_id`가 null"이 동시성 가드다. 두 번째 동시 요청은 잠금 해제 후 멱등 캐시 히트 또는 `409 TURNING_POINT_ALREADY_RESOLVED`.
  - **기존 `idempotency_keys` 재사용 가능**: PK `(game_id, key)`를 유지하고 `endpoint` 컬럼만 추가했다(V8). 응답 형태가 다른 두 엔드포인트가 같은 키를 쓰면 잘못된 응답을 돌려줄 수 있어서 `endpoint`를 비교한다. 클라이언트는 항상 새 UUID를 쓰면 되므로 실사용에서 걸리지 않는다.
- 선택 뒤 프론트는 `GET /api/games/{gameId}`로 재조회해도 되고, 선택 응답의 `game`을 그대로 써도 된다(둘 다 같은 `GameState`).
- `GameState.pendingTurningPoint = EXISTS(SELECT 1 FROM game_turning_points WHERE game_id=? AND resolved_at IS NULL)`. `GameRepository.state()`에 이 서브쿼리 한 줄을 추가한다.

### 3.4 엔딩과의 상호작용

- 분기에서 엔딩이 확정되면 전환점을 만들지 않는다. 이미 대기 중인 전환점이 있는데 엔딩이 났다면(advance 자체가 3b에서 막히므로 실제로는 발생하지 않지만, 매각·관리자 조작 경로 대비) 미해결 행을 `resolved_at=now(), success=NULL`로 닫지 않고 그대로 두되 `status='ENDED'`인 게임은 `GET turning-point`가 `204`를 준다. (파트 2도 대기 중에는 매각을 막는다).
- 매각(`POST /sell`, 파트 2)은 대기 중 전환점이 있으면 `409 TURNING_POINT_PENDING`으로 막는다(메인 결정 Q4). advance와 같은 가드이므로 파트 2는 같은 체크 한 줄을 재사용한다.

---

## 4. 동네 반응과 이번 분기의 순간

### 4.1 반응 선택 규칙

1. 결과 버킷 계산(2.2의 `bucket.*`, `lastResolved.*`). 직전 분기 값은 `turn_results`에서 읽는다(`turn-1` 행이 없으면 `FLAT`/`SAME` 기준으로 계산하고 `bucket.firstQuarter=true`).
2. `reaction_templates WHERE enabled` 전체를 `code ASC`로 읽고 조건식 평가로 후보를 만든다.
3. 뽑을 개수 `n`: `REACTION_PICK` 스트림으로 `event.reaction.min(2)`~`max(3)` 중 하나.
4. 가중 추첨 `n`회, 같은 code 중복 금지(뽑힌 항목 제외 후 누적 가중치 재계산). REVIEW 후보가 있으면 **첫 뽑기는 REVIEW 풀에서** 한다(결산 카드에 리뷰가 최소 1개 보이도록).
5. 후보가 `n`보다 적으면 있는 만큼만. 0개면 `reactions = []`(1.4 조건 4와 일치).
6. `likes`는 `REACTION_LIKES` 스트림에서 `3 + rng.nextInt(60)`에 버킷 보정(`bucket.profit == UP`이면 ×2). 템플릿에 저장하지 않는다.
7. `{shopName}`, `{areaName}`, `{serviceName}` 세 개만 치환(`shops`, `trade_areas.name`, `service_industries.name`). 다른 중괄호는 저장 시 거부. (TASK-007B) 뒤에 조사 쌍을 붙인 `{shopName:이/가}`, `:을/를`, `:은/는`, `:과/와`, `:이에요/예요`는 값의 끝 글자 받침(한글 종성, 숫자는 한국어 읽기, 영문은 발음 근사, 그 밖은 `이(가)` 병기)에 맞는 조사를 붙인다(`EventText`).
8. 저장: `turn_results.reactions`(V5에 이미 존재하는 jsonb 컬럼)와 `response`의 `reactions` 필드 양쪽. 응답 형식은 계약 3장 그대로 `[{kind, author, rating?, text, likes}]`.

### 4.2 순간(moment)

1. `moment_templates WHERE enabled`를 `code ASC`로 읽고 조건 평가 → 후보.
2. `MOMENT_PICK` 스트림 가중 추첨으로 1개(분기당 최대 1개, `event.moment.perQuarter`).
3. 성공 판정: `p = 0.5 + (stat − difficulty) × 0.02`, clamp `[0.05, 0.95]`, `MOMENT_JUDGE` 스트림 난수와 비교.
4. 작은 효과(`on_success`/`on_fail`) 적용. 범위는 2.4의 좁은 부분집합(`STAT`, `MODIFIER`, `TEXT`만. 현금 없음).
5. 응답 `moment = {title, text, stat, success}` (계약 3장). `text`는 성공/실패 문구. 후보 0개면 `null`.
6. 저장: `response.moment` + `turn_results.moment`(V8 신규 컬럼, 집계·관리자 조회용).

### 4.3 가상 경쟁점 / 경쟁점 폐업 (DESIGN 8장 "이벤트 확률")

- 신규: `area.opening_count`가 1 이상인 분기에, `VIRTUAL_RIVAL` 스트림 난수 < `min(0.6, opening_count / max(1, store_count))`면 가상 경쟁점 1개(`rivals.is_virtual=true`)를 추가한다. 분기 상한 `event.rival.virtualPerQuarterCap`.
- 폐업: `RIVAL_CLOSURE` 스트림 난수 < `min(0.5, closing_count / max(1, store_count))`면 가상 경쟁점 중 가장 오래된 1개를 `closed_turn`으로 닫는다. **실제 점포(`sbiz_store_id`가 있는 행)는 닫지 않는다** — 실제 가게에 가상 폐업 연출을 붙이지 않는다(AGENTS.md 규칙 6).
- 이 변화는 다음 분기의 `rivalCount`(밀집 보정)에 반영된다. 문구로 노출할 때도 상호 없이 "같은 업종 가게가 한 곳 늘었어요" 식으로만 쓴다.

---

## 5. V8 스키마

전체 SQL은 `V8__events.sql`. 요약:

| 테이블 | 역할 | DESIGN 11장과의 차이 |
| --- | --- | --- |
| `turning_points` | 정의(조건, 기본 확률, 쿨다운, 우선순위, enabled, rev) | ERD의 `(id, code, title, condition_expr, base_probability, enabled)`에 `stat_bonus, prob_min/max, cooldown_quarters, max_fires, min_turn, priority, rev` 추가 |
| `turning_point_options` | 선택지(라벨, 성공 공식, on_success/on_fail, 문구) | ERD와 동일 + `order_no`, `success_text/fail_text` |
| `game_turning_points` | 게임별 발생·선택 이력 + `definition_rev`, `payload` 스냅샷 | ERD와 동일 + 스냅샷·`applied_effects` |
| `reaction_templates` | 반응 문구(kind, author, rating, weight, 조건) | ERD와 동일 + `author, rating, weight, code, rev` |
| `moment_templates` | 순간 정의(stat, difficulty, 문구, 작은 효과) | **ERD에 없던 테이블** → DESIGN 11장에 추가 필요 |
| `game_modifiers` | N분기 지속 보정 | **ERD에 없던 테이블** → DESIGN 11장에 추가 필요 |
| `game_flags` | 상태 플래그 | **ERD에 없던 테이블** → DESIGN 11장에 추가 필요 |
| `games.shop_count` | 매장 수(1~5) | 메인 결정 2 |
| `idempotency_keys.endpoint` | advance/choice/sell 구분 | 3.3 |
| `turn_results.moment` | 순간 사본 | `reactions`는 V5에 이미 있음 |

주요 제약·인덱스
- `code` 정규식 CHECK(`^[A-Z][A-Z0-9_]{2,39}$`), `jsonb_typeof` CHECK로 조건식은 object, 효과는 array만.
- 확률 컬럼 `BETWEEN 0 AND 1`, `prob_max >= prob_min`.
- 선택지 2~3개는 `DEFERRABLE INITIALLY DEFERRED` 제약 트리거로 트랜잭션 끝에 검사(행 CHECK로 표현 불가).
- `reaction_templates`: `CHECK ((kind='REVIEW') = (rating IS NOT NULL))`.
- `game_turning_points`: `UNIQUE(game_id, turn)`(분기당 1개) + `CREATE UNIQUE INDEX ... (game_id) WHERE resolved_at IS NULL`(게임당 미해결 1개) + `(game_id, turning_point_id, turn DESC)` 이력 조회.
- `game_modifiers`: `end_turn > start_turn AND end_turn - start_turn <= 8`, MULTIPLY value 0.1~3.0, `(game_id, start_turn, end_turn, target, id)` 인덱스.
- 밸런스 상수는 기존 `game_settings`에 `event.*` 키로 `ON CONFLICT DO NOTHING` INSERT(관리자 화면에서 수정).

---

## 6. 관리자 화면과 정의 수정의 영향

기존 `web/AdminController` + Thymeleaf에 붙인다(DESIGN 16장: 관리자는 서버 렌더링).

- 목록: `/admin/events/turning-points`, `/admin/events/reactions`, `/admin/events/moments` — code, title, 활성 여부, 최근 30일 발생 수(`game_turning_points` 집계), rev.
- 활성 토글: `POST .../{id}/toggle` → `enabled` 반전 + `rev + 1`, PRG 리다이렉트.
- 수정 폼: 조건식·효과는 JSON textarea. 저장 시 `EventDefinitionValidator`가 **조건식·확률 공식·효과 배열 전부 검증**하고 위반하면 폼에 오류 경로를 표시(저장 안 함). 검증 통과 시 `rev + 1`, `updated_at = now()`.
- 삭제는 제공하지 않는다(과거 게임이 참조). 비활성만.

**진행 중 게임에 미치는 영향 — 결정**
1. **정의 버전을 게임 단위로 고정하지 않는다.** 이유: `game_data_pins` 같은 핀을 이벤트까지 넓히면 테이블·조회가 복잡해지고, 기말 범위에서 얻는 가치가 작다.
2. 대신 **대기 중인 전환점은 발생 시점에 완전히 고정한다**: `game_turning_points.payload`에 제목·설명·선택지·계산된 성공률·효과 요약을, `definition_rev`에 리비전을 스냅샷한다. 플레이어가 보고 있는 화면과 선택 결과가 관리자 수정으로 뒤바뀌지 않는다.
3. 효과 적용은 `payload`가 아니라 **스냅샷된 효과 JSON**(payload 안에 원본 효과 배열도 함께 저장)으로 수행한다. 즉 선택 시점에 정의 테이블을 다시 읽지 않는다.
4. 이미 해결된 전환점과 적용된 보정(`game_modifiers`)은 정의 수정의 영향을 받지 않는다(값이 이미 복사됨).
5. 비활성화·조건 변경은 **다음 분기의 발생 판정**부터 적용된다.
6. 재현성 정의에 "콘텐츠 리비전"이 포함된다(1.4 조건 6). 관리자가 밸런스를 바꾸면 그 이후 분기의 판정이 달라질 수 있고, 이는 의도된 동작이다.

---

## 7. 테스트 전략

단위 (DB 없음)
1. `ConditionEvaluatorTest`: 연산자 8종, `all/any/not`, 빈 객체, 결측 변수 false, 깊이·노드 수 초과 거부, 타입 불일치 거부.
2. `ExpressionValidatorTest`: 알 수 없는 변수/연산자/효과 type/초과 범위 각각에 대해 거부 메시지 경로 확인. 전환점 효과 전체 집합 통과, 순간에서 `FLAG`/`SHOP_COUNT` 거부.
3. `ProbabilityFormulaTest`: clamp 상·하한, 능력치 40 기준점, `per10` 음수 보정, `successRatePct` 반올림.
4. `EventRandomTest`: (a) 같은 입력 → 같은 값, (b) 전환점 B 추가가 A의 난수를 바꾸지 않음, (c) 이벤트 스트림 시드 ≠ 매출 스트림 시드(모든 purpose·code 조합에서 `mix64(...) != seed ^ (turn*GOLDEN)`), (d) 1만 회 분포가 목표 확률 ±3%p.
5. `ModifierApplicationTest`: 빈 목록이면 기존 결과와 **완전히 동일**(바이트 재현성 조건 2), ADD→MULTIPLY 순서, costRate clamp, visitors 보정이 capacity 상한에 먹히는지.

통합 (Testcontainers, 기존 `GameIntegrationTest` 스타일)
6. **이벤트 off 재현성**: 이벤트 테이블 TRUNCATE 상태에서 004A 골든 JSON과 바이트 동일.
7. **이벤트 on 재현성**: 고정 콘텐츠를 심고 같은 시드·같은 선택으로 두 게임 4분기 → 결과 JSON·현금·원장 동일. 새 골든 파일로 저장.
8. 전환점 대기 중 advance → `409 TURNING_POINT_PENDING`. 선택 후 advance 성공.
9. 선택 멱등: 같은 `Idempotency-Key` 2회 → 같은 응답, 효과 1회만 적용(현금·보정 행 수 확인). 다른 엔드포인트가 같은 키 → `409 IDEMPOTENCY_KEY_REUSED`.
10. 동시 선택: `CountDownLatch`로 두 스레드 동시 요청 → 하나만 해결, 보정 1건.
11. 쿨다운·`max_fires`: 조건을 항상 참·확률 1로 심고 8분기 진행 → 발생 수가 `max_fires` 이하, 간격이 `cooldown_quarters` 이상, 분기당 1개 이하.
12. 분기당 1개 제약: `UNIQUE(game_id, turn)` 위반 시 `DataIntegrityViolationException`.
13. 보정 만료: `quarters=2` 보정이 2분기 뒤 결산에서 사라지는지(매출 비교).
14. 반응 결정론·형식: 같은 상태에서 2~3개, REVIEW 최소 1개, `rating` 범위, 치환 변수 외 중괄호 없음.
15. 엔딩 우선: 잔액 2분기 연속 음수로 CLOSED가 나는 분기에는 `game_turning_points` 행이 생기지 않음.
16. 콘텐츠 검증: V8 콘텐츠 INSERT 후 모든 정의가 `EventDefinitionValidator`를 통과(실제 데이터 기반 회귀), 문구에 금지어(실제 플랫폼·매체 이름 목록) 없음.
17. 관리자: 잘못된 JSON 저장 시 400·폼 오류, 토글 시 `rev` 증가, 대기 중 전환점의 `payload`가 정의 수정 후에도 그대로.

---

## 8. 결정된 질문과 남은 질문

Q1~Q7 중 Q1~Q6은 메인이 2026-11-18에 답했다(2장 머리의 "추가 반영" 참고). 요약:

| 질문 | 결정 |
| --- | --- |
| Q1 선택 후 version 사용 | 선택 응답의 `game.version`을 다음 요청 `expectedVersion`으로 쓴다. 계약에 명시 |
| Q2 전환점 알림 위치 | `GameState.pendingTurningPoint`만. `QuarterResult` 불변 |
| Q3 순간의 효과 범위 | 즉시 현금 금지. `STAT`·`MODIFIER`·`TEXT`만 |
| Q4 대기 중 매각 | `409 TURNING_POINT_PENDING`. `game_records` 표시 불필요 |
| Q5 DESIGN 갱신 | 메인이 11·16장 갱신 |
| Q6 `shop_count` | `games.shop_count` → `game_records.shop_count` 복사, 폐업 엔딩에도 값 유지 |

남은 질문 하나:

**Q7. 가상 경쟁점 변화의 노출.** `areaNews`는 실제 데이터 기반이고, 가상 경쟁점 변화는 어디에 보여줄지 계약에 없다. 초안은 **반응 템플릿 문구로만** 간접 노출(필드 추가 없음). 별도 필드가 필요하면 파트 2 계약 개정 때 같이.

---

## 9. 검증 기록 (2026-11-18)

### 9.1 V8 SQL 실제 적용 검증
임시 컨테이너(`postgres:16-alpine`)에 V5 의존 테이블(`games`, `turn_results`, `idempotency_keys`, `game_settings`)을 최소 스텁으로 만든 뒤 `V8__events.sql` 전체를 `psql -v ON_ERROR_STOP=1`로 적용했다. 결과: 오류 없음.

```
ALTER TABLE ×3, CREATE TABLE ×6, CREATE FUNCTION, CREATE TRIGGER,
CREATE INDEX ×7, COMMENT ×4, INSERT 0 10 (game_settings event.* 10건)
```

미검증: 실제 V5~V7 위에서의 Flyway 순차 적용(스텁으로만 확인), PostGIS 이미지에서의 적용. TASK-004B 구현 때 `gradlew check`의 Testcontainers 경로에서 다시 확인해야 한다.

### 9.2 CONTENT_DRAFT.md (Gemini) DSL 적합성 검사
`CONTENT_DRAFT.md`의 JSON 블록 14개를 파싱해 이 문서의 화이트리스트로 기계 검증했다. 검증 항목: 조건 노드 형식·깊이·노드 수, 변수 화이트리스트(전환점·순간은 `bucket.*`/`lastResolved.*` 금지, 반응만 허용), 연산자, 확률 공식 범위, 효과 type·키·수치 범위, 순간의 좁은 화이트리스트, code 형식·중복, 치환 변수, REVIEW/COMMUNITY의 rating 규칙.

- 1차: 순간 7개에서 `CASH` 효과 9건 위반(메인 Q3 결정 전에 작성됨) → Gemini가 `STAT`/`MODIFIER`로 교체.
- 2차(최종): **위반 0건.** 전환점 12개, 반응 36개(REVIEW 24 / COMMUNITY 12, 조건 없는 범용 REVIEW 3개), 순간 12개(능력치 6종 각 2개).
- 교차 참조: 반응이 참조하는 `lastResolved.code` 3개(`CREATOR_COLLAB`, `HYGIENE_INSPECTION`, `LOCAL_FESTIVAL`)가 모두 정의에 존재. 조건에서 읽는 `flag.FRANCHISE_CONTRACT`는 `FRANCHISE_OFFER` 효과가 세팅한다(미정의 플래그 참조 0건).
- 초반 커버리지: 1~4분기에 발생 가능한 전환점 9개, 조건 없는 순간 9개 → 초반 분기에 빈 화면이 나지 않는다.

관찰(밸런스, 구현·플레이 테스트 때 확인)
1. `FLAG` 6개 중 조건에서 읽히는 것은 `FRANCHISE_CONTRACT` 하나다. 나머지 5개(`DELIVERY_ENABLED`, `CREATOR_HIT`, `HYGIENE_CERTIFIED`, `FESTIVAL_STAR`, `VIRAL_HIT`)는 현재 상태 기록 용도뿐이다. 후속 콘텐츠에서 참조하면 되므로 유지한다.
2. `RENT_HIKE`, `REDEVELOPMENT_RUMOR`는 `area.salesTrendPct` / `area.closingRateDelta`에 의존하지만 `any`로 다른 조건과 묶여 있어 결측 상황에서도 발생 가능하다 — 의도대로다.
3. `SECOND_BRANCH`의 `maxFires=2`는 `shop_count` 상한 5와 충돌하지 않는다(최대 3호점).
4. `BURNOUT`이 `priority=80`으로 가장 높아 다른 전환점을 밀어낸다. 의도된 설계지만 같은 상태가 이어질 때 `cooldown_quarters=5`가 길게 느껴질 수 있다(관리자 화면에서 조정 가능).

검증은 일회성 스크립트로 수행했다. TASK-004B 구현 시 9.2의 검사 항목을 `EventDefinitionValidator` 단위 테스트와 "V8 콘텐츠 전수 검증" 통합 테스트(7장 16번)로 코드에 남긴다.
