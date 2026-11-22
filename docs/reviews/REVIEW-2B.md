# Stage 2 코드 리뷰 (Context-Free): 907f8b8..175ebb8

대상 저장소: `C:\Users\my\dev\seoul-startup-sim`
검토 범위: `907f8b8..175ebb8` (56개 변경 파일)

---

## Findings

### [MEDIUM] 1. `EventEffectService.modifierValue()`가 소수점 `%`/`%p`를 정수로 반올림해 `V14_2` 라벨과 모순된 수치(`+0%p` 등)를 표시함

- **위치**:
  - `backend/src/main/java/com/seah/startupsim/game/EventEffectService.java:95-103`
  - `frontend/src/components/GameMilestones.tsx:64-68`
- **근거**:
  - `V14_1__minor_event_content.sql:254-264`에서 `MINOR` 전환점의 `MODIFIER` 수치를 25%로 축소하면서 `costRate` `+0.01`은 `+0.0025`(+0.25%p), `-0.01`은 `-0.0025`(-0.25%p), `revenue`/`visitors` `1.06`은 `1.015`(+1.5%) 등이 되었다.
  - `V14_2__minor_modifier_labels.sql:4-12`는 이를 반영해 `label`을 소수 첫째 자리까지 생성한다(예: `원가율 +0.3%p · 2분기`, `원가율 -0.3%p · 2분기`, `매출 +1.5% · 2분기`).
  - 반면 `EventEffectService.modifierValue()`는 `Math.round(...)`와 `%+d%%` / `%+d%%p` 포맷을 사용한다. 그 결과 `costRate` `0.0025`와 `-0.0025`는 `Math.round(±0.25) = 0`이 되어 `"+0%p"`로, `revenue` `1.015`는 `"+2%"`로 변환된다.
  - 선택 결과 화면(`TurningPointCard`)에서 `result.effects`를 `{e.label} {e.value}`로 함께 출력하므로 사용자에게 `원가율 +0.3%p · 2분기 +0%p`, `매출 +1.5% · 2분기 +2%`처럼 앞뒤가 맞지 않는 수치가 노출된다.
- **수정 방안**:
  - `EventEffectService.modifierValue()`에서 `MULTIPLY`와 `costRate`를 `V14_2__minor_modifier_labels.sql`과 동일하게 소수 첫째 자리 반올림(끝자리 `.0` 제거)으로 포맷한다. 예: `0.25%p` -> `+0.3%p`, `-0.25%p` -> `-0.3%p`, `1.5%` -> `+1.5%`.

### [MEDIUM] 2. `SECOND_SHOP` 목표가 `TurningPointService.choose()` 시점에 판정되지 않음

- **위치**:
  - `backend/src/main/java/com/seah/startupsim/game/TurningPointService.java:74-83`
  - `backend/src/main/java/com/seah/startupsim/game/AdvanceService.java:52`
- **근거**:
  - `MissionRepository.evaluate()`는 `AdvanceService.advance()`에서만 호출된다.
  - 반면 `games.shop_count`를 증가시키는 `EventDsl.ShopCount` 효과는 `TurningPointService.choose()` 내부의 `effects.apply(...)`에서만 실행된다.
  - 따라서 `SECOND_SHOP` 목표를 선택한 플레이어가 전환점 선택으로 `shop_count`가 2가 되어도:
    1. `choose()` 응답의 `game.mission`은 `progress`가 `2 / 2곳`으로 표시되면서 `achieved`는 `false`, `achievedTurn`은 `null`로 내려간다.
    2. 다음 분기 `advance()` 전에 매각(`POST /api/games/{id}/sell`)하면 `evaluate()`가 한 번도 실행되지 않아 `game_records.mission_achieved`가 `false`로 기록된다.
    3. 다음 분기에 `advance()`를 진행하더라도 `achievedTurn`이 실제 달성 시점(`pending.turn()`)이 아니라 다음 분기(`turn + 1`)로 기록된다.
- **수정 방안**:
  - `TurningPointService.choose()`에서 `effects.apply(...)` 호출 직후, `games.state(gameId, userId)`를 만들기 전에 `MissionRepository.evaluate(games.jdbc(), gameId, pending.turn())`를 호출한다.

### [MEDIUM] 3. 프론트엔드 목(`mock`)의 `AREA_TOP3` `progress.target` 및 `SECOND_SHOP` 라벨이 계약과 불일치함

- **위치**:
  - `frontend/src/api/mock/index.ts:47, 63, 116`
- **근거**:
  - `API_CONTRACT.md:386, 409`와 `Mission.java:9`에서 `AREA_TOP3`("동네 5위 안")의 `progress.target`은 `5`다.
  - 그러나 `frontend/src/api/mock/index.ts:116`의 `initialMission`은 `target: [12, 100000000, 3, 4, 2][index]`로 설정해 `AREA_TOP3`의 `target`이 `3`으로 내려간다.
  - 같은 파일 706행의 달성 판정은 `current <= 5`를 사용하고 `GamePage.tsx:201`의 진행률 바는 `progress.target`을 사용하므로, 목 모드에서 4~5위일 때 `achieved: true`이면서 `target`은 `3`인 상태가 된다.
  - 또한 목의 `SECOND_SHOP` `targetLabel`은 `'2개 매장'`(`label`은 `'...개 / 2개 매장'`)인데, 백엔드 `Mission.java:11, 39`는 `'2곳'`(`'... / 2곳'`)이다.
- **수정 방안**:
  - `frontend/src/api/mock/index.ts:116`의 `3`을 `5`로 수정하고, 필요시 `SECOND_SHOP`의 `targetLabel`/`missionLabel`도 백엔드(`2곳`)와 맞춘다.

### [LOW] 4. `EventEngine.pickMinor()`의 가중치 추첨 루프가 부동소수점 오차 시 `null`을 반환할 수 있음

- **위치**:
  - `backend/src/main/java/com/seah/startupsim/game/EventEngine.java:186-191`
- **근거**:
  - `total`에 `nextDouble()`(`1.0`에 매우 가까운 값)을 곱한 `draw`가 반올림으로 `total`과 같아진 뒤, 각 후보의 확률을 순차 차감할 때 부동소수점 오차(1 ULP)로 마지막까지 `draw >= 0`이 남으면 루프를 빠져나와 `null`을 반환한다.
- **수정 방안**:
  - 루프 종료 후 `return null;` 대신 `return eligible.isEmpty() ? null : eligible.getLast();`를 반환한다.

### [LOW] 5. `EventEngine.fire()`가 `points`의 `priority DESC` 정렬을 암묵적으로 가정함

- **위치**:
  - `backend/src/main/java/com/seah/startupsim/game/EventEngine.java:83-94`
- **근거**:
  - 첫 번째로 통과한 후보의 `priority`를 `highest`로 두고 같은 값만 모은다. 현재 `EventDefinitionRepository.turningPoints()`는 `ORDER BY p.priority DESC, p.code`를 보장하지만, `pickMinor()`처럼 명시적으로 최댓값을 구하지 않아 호출자나 테스트에서 정렬되지 않은 리스트를 넘기면 최우선순위가 아닌 후보가 선택될 수 있다.
- **수정 방안**:
  - `pickMinor()`와 마찬가지로 `judged` 통과 후보 중 `priority` 최댓값을 먼저 계산해 필터링한다.

### [LOW] 6. `GameMilestones.tsx`의 효과 칩 렌더링에서 `e.label`을 React `key`로 사용함

- **위치**:
  - `frontend/src/components/GameMilestones.tsx:64-68`
- **근거**:
  - `result.effects.map((e) => (<span key={e.label}>...))`에서 동일한 `label`을 가진 효과가 2개 이상(예: 동일 메모의 현금 효과 등) 포함되면 React key 중복 경고가 발생한다.
- **수정 방안**:
  - `map((e, index) => ... key={`${e.label}-${index}`})` 형태로 고유 키를 부여한다.

---

## 요약

| 위험도 | 건수 |
| --- | ---: |
| HIGH | 0 |
| MEDIUM | 3 |
| LOW | 3 |
| **합계** | **6** |
