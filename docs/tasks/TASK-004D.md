# TASK-004D: 밸런스 시뮬레이터 + COPY 개선 + 비게임 패키지 코드 정리

- 브랜치: `task/TASK-004D` (worktree `C:\Users\my\dev\sss-004d`, DB 5436, 앱 8084)
- 담당: Codex
- 선행: `AGENTS.md`, `docs/DESIGN.md` 8장, `docs/benchmarks/BULK_LOAD.md`, `backend/` (특히 `game/QuarterCalculator`, `catalog/store`, `area`, `catalog`)
- **Flyway: `V10__balance.sql` 만 사용** (V8·V9는 다음 작업 예약)
- **game 패키지 코드는 수정 금지** (004A가 리팩터링 중). 읽기만.

## 1. 밸런스 시뮬레이터 (가장 중요)
실제 데이터로 한 판을 해 보니 커피 가게가 매 분기 200만 원씩 손해다(매출 3,300만 원 / 비용 3,570만 원). 게임으로서 균형이 맞는지 수치로 보고 조정한다.
- `@Tag("simulation")` 테스트 또는 Gradle 태스크 `simulate` (check에 포함하지 않음). **실제 데이터가 있는 메인 DB(localhost:5433)** 를 읽기 전용으로 사용 (`.env`의 DB 접속 정보, 이 worktree .env의 DB_URL을 5433으로 덮어쓰는 시스템 프로퍼티 허용). 메인 DB에 쓰기 금지 — 계산은 `QuarterCalculator`를 직접 호출해 메모리에서.
- 시나리오: 외식 10개 업종 × 무작위 상권 200곳(매출 자료 있는 곳) × 정책 3종(보수적: 가격 NORMAL·직원 1·마케팅 낮음 / 표준 / 공격적) × 시작 분기 2021~2023 → 12분기 진행
- 출력 `docs/balance/BALANCE_REPORT.md`: 업종별·정책별 12분기 생존율, 분기 순이익 분포(p10/p50/p90), 파산까지 평균 분기, 상권 유형별 차이
- **목표**: 표준 정책 기준 생존율 40~70%, 업종 간 차이는 실제 데이터의 차이를 반영하되 한 업종이 0% 또는 100%가 되지 않게
- 조정 수단은 `game_settings`(임대료, 원가율, 인건비, 매력도 가중치, 시작 자본)만. `V10__balance.sql`에서 UPDATE. 조정 전후 표를 모두 보고서에 남기고, 각 값을 왜 바꿨는지 근거를 적는다(예: 업종별 원가율의 일반적 범위).
- `QuarterCalculator`의 공식 자체를 바꿔야 한다고 판단되면 고치지 말고 "제안"으로 보고(004A 충돌 방지).

## 2. COPY 적재 개선
`BULK_LOAD.md` 해석 2번: 지금은 1,000행마다 스테이징 생성·삭제 → COPY가 JDBC 배치보다 10%만 빠름.
- 파일 전체를 **한 번의 COPY 스트림**으로 스테이징(TEMP 또는 UNLOGGED)에 넣고, 한 번의 `INSERT … SELECT … ON CONFLICT`로 반영하는 `StreamingCopyStoreBulkLoader` 추가(기존 구현은 비교용으로 유지)
- 메모리: 전체를 문자열로 만들지 말고 `PipedReader`/`Reader` 스트리밍
- 벤치마크에 추가하고 **본인 worktree DB(5436)** 에서 1만/10만/전체 재측정, `BULK_LOAD.md`에 행 추가(측정 시각, 동시 부하 여부 기록). JPA 전체 규모는 측정하지 않는다.
- 빨라지면 운영 수집기 기본 전략을 교체

## 3. 코드 정리 (동작 변경 없음)
대상: `area`, `catalog`, `catalog/store`, `ingest`의 TASK-002·003 추가분, `StoreBenchmarkTest` 등 테스트. game 패키지 제외.
- 4칸 들여쓰기, 한 줄 한 문장, 명시 import, SQL 텍스트 블록, 핵심 클래스 한국어 Javadoc
- 패키지 정리: `area`를 `catalog.area`로 옮길지 판단(옮기면 import만 바뀌게)하고 이유 보고

## 완료 조건
1. `gradlew.bat clean check --offline` 통과
2. `BALANCE_REPORT.md` (조정 전후), `BULK_LOAD.md` 갱신
3. 메인 DB(5433)에 쓰기가 없었음을 보고(사용한 쿼리가 SELECT뿐)
