# TASK-003A: 게임 엔진 핵심 (게스트, 사장님, 게임, 창업, 분기 진행)

- 브랜치: `task/TASK-003A` (worktree `C:\Users\my\dev\sss-003a`, DB 5434, 앱 8082)
- 담당: Codex
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 2~8장·11장, **`docs/api/API_CONTRACT.md` 2장(게임)과 공통** — 이 계약이 기준
- **Flyway: `V5__game.sql` 만 사용**. 다른 작업: 003C(V6), 003D(V7)

## 목표
DESIGN의 핵심 이야기 3·5번 "재현 가능한 시뮬레이션"과 "턴 진행 정합성"을 만든다. 게임은 서버가 판정하고, 같은 시드·선택·데이터 버전이면 결과가 같다.

## 만들 것
1. `V5__game.sql`: DESIGN 11장 "회원", "게임" 테이블 (users, games, game_data_pins, owners, shops, rivals, turn_decisions, turn_results, ledger_entries, game_settings) + `idempotency_keys (game_id, key, response jsonb, created_at, PK(game_id,key))`
   - `turn_results`, `turn_decisions`는 `UNIQUE(game_id, turn)`
   - `games.version` 낙관적 락용, `cash`는 ledger 합계와 일치해야 함
   - `game_settings` 기본값 INSERT: 자치구별 분기 임대료(25개 구 고정값, 근거는 "v1 임의값" 주석), 업종 분류별 원가율, 분기 인건비/1인, 매력도 가중치, 시작 자본 등
2. 게스트 인증: `POST /api/guest`, `X-Guest-Token` 필터/리졸버. 토큰은 SHA-256 해시 저장. 다른 사람 게임 접근은 403.
3. 사장님 후보: 계약대로. background별 분포 템플릿 + seed 기반 변형, total=240 보장
4. 게임 생성, 창업(상권 폴리곤 포함 판정 `ST_Contains`, 반경 300m 실제 같은 업종 점포 → rivals 복사), 결정 저장, 분기 진행, 조회
   - 업종 연결: 상가업소(stores)는 소상공인 소분류 코드라서 서울 서비스업종 코드와 다르다. 003D가 매핑을 만들기 전까지는 **`docs/data/industry_mapping_draft.csv`의 exact 매핑만** 쓰는 `IndustryMappingPort` 인터페이스를 두고 임시 구현을 넣는다(003D가 교체).
5. 분기 진행 (`AdvanceService`) — 한 트랜잭션:
   1) Idempotency-Key가 이미 있으면 저장된 응답 반환
   2) `SELECT … FOR UPDATE`로 게임 잠금, expectedVersion 확인
   3) 이번 turn의 decision 로드 (없으면 400)
   4) 계산 (`QuarterCalculator`, 순수 함수 — DB 접근 없이 입력 스냅샷만 받음):
      - 시장 규모 = area_sales_q 분기 매출(분기 합계, 환산 없음). 없으면 같은 자치구·업종 점포당 매출 × 상권 점포 수, `dataNotes`에 추정 표시
      - 점유율, 매력도, 밀집 보정, 처리량 상한은 DESIGN 8장. 랜덤은 `SplittableRandom(seed ^ turn * 상수)`처럼 **턴별로 독립된 결정론적 난수**
      - 비용: 임대료(자치구), 원가, 인건비, 마케팅
      - 능력치 성장: ownerActivity, focusStats, 영업 경험
      - areaNews: area_stores_q의 개업/폐업 수
   5) turn_results, ledger_entries INSERT, games 갱신(current_turn+1, version+1, cash)
   6) 엔딩 판정: 잔액 2분기 연속 음수 → CLOSED, 데이터 마지막 분기 도달 → LONG_RUNNING
   7) idempotency_keys 저장
   - game_data_pins: 게임 생성 시 각 출처의 ACTIVE data_version을 고정하고, 계산은 그 버전의 데이터만 읽는다
6. 에러는 계약의 코드/형식대로 (`common`의 에러 형식 재사용)

## 테스트 (가장 중요)
- `QuarterCalculator` 단위 테스트: 같은 입력 → 같은 출력, 경계값(매출 null, 점포 0, 처리량 상한)
- 재현성: 같은 seed와 결정으로 게임 2개를 4분기 진행 → turn_results 완전히 동일
- 동시성: 같은 게임에 advance 2개를 동시에(스레드 2개, 다른 Idempotency-Key, 같은 expectedVersion) → 하나만 200, 하나는 409, turn_results 1행
- 멱등성: 같은 Idempotency-Key 두 번 → 같은 응답, turn_results 1행
- DB 제약이 마지막 방어선: 서비스 우회해서 같은 (game_id, turn) INSERT → DB가 거절
- 원장: 4분기 후 `games.cash == 시작자본 + sum(ledger)`
- 권한: 다른 게스트 토큰으로 조회 → 403
- 테스트 데이터: 작은 픽스처(상권 2개, 분기 매출/점포 몇 행, 상가업소 몇 개)를 테스트용 SQL로 넣는다

## 하지 않을 것
전환점, 동네 반응, 랭킹, 카카오 로그인, 프론트엔드, 관리자 화면

## 완료 조건
1. `gradlew.bat clean check --offline` 통과(테스트 수). 의존성 추가가 필요하면 build.gradle에 표준 좌표로 적고 메인에게 "의존성 준비 요청"
2. 로컬 실제 데이터 시나리오: 이 worktree DB(5434)에는 상권(V2)만 있다. 메인 저장소 DB(5433)에 실제 데이터가 있으니 **완료 조건 2는 메인 에이전트가 검증**한다. 대신 시나리오를 재현하는 curl/PowerShell 스크립트를 `docs/tasks/TASK-003A-demo.ps1`로 남긴다(게스트→후보→게임→창업(망원 상권)→결정→진행 4회)
3. 키·토큰이 커밋/로그에 없음
