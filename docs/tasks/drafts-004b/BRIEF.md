# TASK-004B 설계 브리프 (메인 에이전트, 2026-11-18)

저장소(main 최신, 읽기 기준): 저장소 루트. 이 폴더(`docs/tasks/drafts-004b/`)에 자기 초안 파일만 쓴다. git commit/push 금지.
다른 에이전트가 별도 `sss-005a`, `sss-005c` 워크트리에서 게시판을 구현 중이다. 그 폴더는 건드리지 않는다.

## 004B가 하는 일 (DESIGN 4~7장, API 계약 3장 v1.1)
- 파트 1 (V8): 전환점 이벤트, 동네 반응, 이번 분기의 순간(moment)
- 파트 2 (V9): 엔딩(매각 포함)과 `game_records`, 요약 API, 랭킹 30분 배치, 공유 페이지 — 메인이 직접 설계
- 기말 범위: 전환점 10~15개, 반응 템플릿, 전체·업종별 랭킹 (DESIGN 17장)

## 현재 코드 사실 (main c7368d4, 메인이 확인)
- `AdvanceService.advance()` 한 메서드가 잠금 → 멱등 키 → 버전 → 결정 조회 → 시장 데이터 → `QuarterCalculator.calculate()` → turn_results/ledger/owners 저장 → **엔딩 판정(CLOSED: 잔액 2분기 연속 음수, LONG_RUNNING: last_quarter 도달)을 인라인으로** → 응답을 idempotency_keys에 저장.
- 난수: `QuarterCalculator`가 `new SplittableRandom(seed ^ (turn * 0x9E3779B97F4A7C15L)).nextDouble(.9, 1.1)` 하나만 쓴다(매출 변동).
- `GameTypes.Result`에 `moment`(현재 null), `reactions`(현재 빈 리스트) 필드가 이미 있다. `turn_results.reactions jsonb DEFAULT '[]'` 컬럼도 V5에 있다.
- 능력치 6종(cooking, service, management, marketing, stamina, network), 활동 4종(MENU_DEV, REGULARS, STUDY, REST), 가격 LOW/NORMAL/HIGH, 영업시간 6~16, 직원 0~10.
- `games`: status PLAYING/ENDED, ending_type, version, cash, negative_quarters, settings jsonb(계산 상수 스냅샷), seed.
- **TASK-004A(리팩터링)가 아직 main에 없다.** 004A가 `TurnEventHook.afterQuarter(ctx, outcome, resultBuilder)`, `GameEndedEvent`, 타입 있는 도메인, `pendingTurningPoint` 필드, 고정 시드 4분기 **바이트 단위 재현성 회귀 테스트**를 만든다. 004B는 004A 병합 뒤 그 위에 올라간다(착수 게이트).

## 지켜야 할 원칙
1. **결정론**: 시드 + 플레이어 선택 + 데이터 버전이 같으면 결과가 같다. 이벤트·반응·순간용 난수는 매출 난수와 **다른 스트림**(목적별로 분리된 시드 파생)이어야 한다. 이벤트가 하나도 발생하지 않는 경로에서는 004A 재현성 회귀 테스트의 결과가 바이트 단위로 그대로여야 한다.
2. **데이터 주도**: 전환점 정의(조건, 선택지, 확률 공식, 효과)와 반응 템플릿은 DB 테이블에 두고 관리자가 수정할 수 있다(DESIGN 5·8장). 조건·효과 표현식은 **화이트리스트 JSON**이어야 한다(임의 코드·SpEL·스크립트 금지).
3. **서버 판정**: 발생·성공 판정은 서버가 한다. 클라이언트는 선택지 id만 보낸다.
4. **멱등·동시성**: 선택 요청은 `Idempotency-Key`(기존 idempotency_keys 재사용 가능 여부 판단). 전환점 대기 중 advance는 409 `TURNING_POINT_PENDING`.
5. **실제 상호 금지**: 반응·순간 문구에 실제 가게·매체·플랫폼 이름 금지. 가상 이름만.
6. Flyway: 파트 1은 `V8__events.sql`, 파트 2는 `V9__endings_ranking.sql`. V10은 TASK-004D, V11은 게시판.

## 산출물
- `ENGINE_DRAFT.md` (Claude): 이벤트 엔진 설계 + V8 SQL 초안
- `CONTENT_DRAFT.md` (Gemini): 전환점 12개 + 반응 템플릿 + 순간 정의, ENGINE_DRAFT의 DSL 형식 그대로
- `ENDING_DRAFT.md` (메인): 파트 2
