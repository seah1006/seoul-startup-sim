# TASK-004A: 게임 패키지 리팩터링 (동작 변경 없음) + 확장 지점

- 브랜치: `task/TASK-004A` (worktree `C:\Users\my\dev\sss-004a`, DB 5434, 앱 8082)
- 담당: Codex
- 선행: `AGENTS.md`, `docs/DESIGN.md`, `docs/api/API_CONTRACT.md`, 현재 `backend/.../game/` 전체와 그 테스트
- **Flyway 추가 없음** (V5는 이미 적용된 DB가 있으니 절대 수정 금지)

## 왜
게임 엔진은 동작하고 테스트도 통과하지만, 코드가 포트폴리오·기말과제로 보여주기 어렵다.
- 한 줄에 여러 문장, 변수명 `r`, `g`, `d`
- 도메인이 `Map<String,Object>`로 흘러 다님, SQL이 한 줄 문자열
- AdvanceService 한 메서드가 조회·계산·저장·멱등 처리를 모두 함
다음 작업(전환점, 엔딩·랭킹)이 이 위에 올라가므로 먼저 정리한다.

## 할 것 (외부 동작·API 응답·DB 스키마는 그대로)
1. **타입 있는 도메인**: `Game`, `Owner`, `Shop`, `TurnDecision`, `QuarterSnapshot`(계산 입력: 시장 규모, 점포 수, 개폐업, 경쟁점 수, 설정값), `QuarterOutcome`(계산 출력) 같은 record. `Map<String,Object>`는 컨트롤러 응답 직렬화 경계에서도 쓰지 말고 응답 record로.
2. **Repository 분리**: `GameRepository`를 역할별로 나눈다(예: `GameRepository`, `TurnRepository`, `LedgerRepository`, `MarketDataRepository`(분기 데이터 조회, 데이터 버전 고정 포함)). SQL은 텍스트 블록으로 여러 줄, 컬럼을 명시.
3. **AdvanceService 단계 분리**: `잠금·검증 → 스냅샷 로드 → 계산 → 저장 → 엔딩 판정 → 응답` 을 private 메서드나 협력 객체로 나눠 한눈에 흐름이 보이게. 트랜잭션 경계는 그대로.
4. **확장 지점** (다음 작업이 여기에 붙는다):
   - `TurnEventHook` 인터페이스: `void afterQuarter(GameContext ctx, QuarterOutcome outcome, TurnResultBuilder result)` — 기본 구현은 아무것도 안 함. 전환점·반응이 여기서 `moment`, `reactions`, 대기 전환점을 채우게 된다.
   - 게임이 끝나면 `GameEndedEvent(gameId, endingType)`를 `ApplicationEventPublisher`로 발행(트랜잭션 커밋 후 처리는 리스너 쪽 `@TransactionalEventListener` 책임).
   - `GameState` 응답에 `pendingTurningPoint`(지금은 항상 false) 필드 추가 — 계약 v1.1
5. **스타일**: 4칸 들여쓰기, 한 줄 한 문장, 의미 있는 이름, 명시 import, 핵심 클래스·메서드 한국어 Javadoc("왜"를 포함: 결정론적 난수, 행 잠금 + 버전 + 멱등 키를 같이 쓰는 이유, 데이터 버전 고정 등)
6. 테스트도 같은 스타일로 정리하고, 계산기 단위 테스트를 경계값 위주로 3개 이상 보강(가격 수준별, 처리량 상한, 매출 추정 경로)

## 지켜야 할 것
- 기존 테스트가 리팩터링 전후 모두 통과해야 한다. 먼저 현재 상태로 테스트를 돌려 기준선을 기록한 뒤 시작.
- **재현성 회귀 검사**: 리팩터링 전 코드로 고정 시드 게임 4분기 결과(JSON)를 테스트 리소스로 저장해 두고, 리팩터링 후 결과가 바이트 단위로 같은지 비교하는 테스트를 추가한다.

## 완료 조건
1. `gradlew.bat clean check --offline` 통과 (테스트 수)
2. 재현성 회귀 테스트 통과
3. game 패키지에서 `Map<String, Object>` 사용 0건(직렬화 경계 포함) — `git grep` 결과 보고
