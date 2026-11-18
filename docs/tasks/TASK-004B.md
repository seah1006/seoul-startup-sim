# TASK-004B: 전환점·동네 반응·순간 (파트 1) + 엔딩·랭킹·공유 (파트 2)

- 담당: Codex. **두 파트는 순서대로, 브랜치를 나눈다.**
  - 파트 1: `task/TASK-004B-1` (worktree `../sss-004b1`, DB 5438, 앱 8086), **Flyway `V8__events.sql`만**
  - 파트 2: `task/TASK-004B-2` (파트 1 병합 뒤), **Flyway `V9__endings_ranking.sql`만**
- **착수 게이트**: TASK-004A(리팩터링, `TurnEventHook`, `GameEndedEvent`, 타입 있는 도메인, 고정 시드 바이트 재현성 회귀 테스트)가 main에 병합된 뒤. 004A 없이 시작하지 않는다.
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 3~8·11·16·17장, `docs/api/API_CONTRACT.md` 3장, 그리고 **규범 문서** (이 지시서와 충돌하면 이 지시서가 우선)
  - `docs/tasks/drafts-004b/ENGINE_DRAFT.md` 1~6장: 난수 스트림, DSL, 흐름, 반응·순간, 스키마, 관리자
  - `docs/tasks/drafts-004b/V8__events.sql`: 스키마 원본 (실제 PostgreSQL에서 V1~V7 위 적용 확인)
  - `docs/tasks/drafts-004b/CONTENT_DRAFT.md`: 전환점 12, 반응 36, 순간 12 (DSL v1.2 기계 검증 위반 0건)
  - `docs/tasks/drafts-004b/ENDING_DRAFT.md`, `V9__endings_ranking.sql`: 파트 2 (V1~V8 위 적용, 공동 순위 쿼리 확인)
- V10은 TASK-004D, V11은 게시판(TASK-005A). V8·V9 병합 뒤에야 V10·V11을 main에 넣을 수 있다(Flyway 순서).

## 왜
지금 게임은 분기 버튼을 누르면 숫자만 나오고, 끝나도 기록이 남지 않는다. 전환점·반응·순간이 "커리어 시뮬레이션"의 재미를 만들고, 엔딩 기록·랭킹·공유가 한 판의 결과를 남긴다. 게시판 RECORD(TASK-005B)도 `game_records`가 있어야 열린다.

---

## 파트 1: 이벤트 엔진 (V8)

### 할 것
1. **V8**: `drafts-004b/V8__events.sql` 스키마를 그대로 쓰고, 파일 끝에 `CONTENT_DRAFT.md`의 JSON 블록(전환점·선택지·반응·순간)을 INSERT로 변환해 붙인다(1회 시드. 이후 수정은 관리자 화면). 변환은 손으로 옮기지 말고 스크립트로 생성하고, 스크립트도 `docs/tasks/drafts-004b/`에 남긴다.
2. **난수**: ENGINE 1장 규칙(`mix64(seed ^ turn×GOLDEN ^ FNV64(purpose|code))`, 목적별 독립 스트림). 매출 난수(`QuarterCalculator`)는 건드리지 않는다.
3. **DSL**: 조건·확률·효과 파서와 **검증기**(화이트리스트, 깊이·노드 상한, 범위). 알 수 없는 키·변수는 저장·로드 시 거부. 임의 코드 실행 수단(SpEL, 스크립트, 리플렉션) 금지.
4. **계산기 반영**: `QuarterCalculator.Input`에 `List<Modifier>` 한 필드만 추가. 보정이 비어 있으면 기존 산술 경로를 그대로 탄다(바이트 동일).
5. **흐름** (ENGINE 3장): 결산 저장 → 엔딩 판정 → `EventEngine.afterQuarter`(004A의 `TurnEventHook` 구현). 엔딩이 난 분기에는 전환점을 만들지 않는다. 한 분기 최대 1개, 쿨다운.
   - `GET /api/games/{id}/turning-point` (200/204), `POST …/turning-point/choice` (`Idempotency-Key` 필수, `idempotency_keys.endpoint='turning-point-choice'`, 같은 키를 다른 엔드포인트에 쓰면 409 `IDEMPOTENCY_KEY_REUSED`). 선택은 `games.version`만 +1, `current_turn` 그대로. 응답의 `game.version`을 다음 요청에 쓴다.
   - 대기 중 advance는 409 `TURNING_POINT_PENDING`. `GameState.pendingTurningPoint`로만 알린다(QuarterResult에 필드 추가 없음).
6. **동네 반응·순간** (ENGINE 4장): 템플릿 2~3개를 결정론적으로 골라 `turn_results.reactions`에, 순간은 `turn_results.moment`에 저장하고 QuarterResult의 기존 `moment`, `reactions` 필드를 채운다. **순간은 현금 효과 없음**(능력치 ±2, 1~2분기 보정만). 화면 아래 "동네 반응은 게임 속 가상 반응입니다" 표기는 프론트 몫.
7. **가상 경쟁점 변화**는 별도 응답 필드 없이 반응 문구로만 노출(메인 결정, 계약 변경 없음).
8. **관리자** (`/admin/turning-points`, `/admin/reactions`, `/admin/moments`, Thymeleaf, 기존 보안·CSRF): 목록, 활성 토글, JSON 편집(저장 시 같은 검증기, 오류 경로 표시). 진행 중 게임에는 이미 대기 중인 전환점만 스냅샷(payload + rev)으로 고정되고, 나머지는 최신 정의를 쓴다(ENGINE 6장).

### 결정론 회귀 (가장 중요)
- 004A의 고정 시드 바이트 재현성 테스트는 픽스처에 `TRUNCATE turning_points, turning_point_options, reaction_templates, moment_templates CASCADE`를 넣어 **이벤트가 없는 경로**에서 그대로 통과해야 한다(파일 내용 수정 금지, 픽스처 한 줄만).
- 이벤트를 켠 상태의 재현성은 **새 골든**으로 기록: 같은 시드·선택으로 두 번 진행한 8분기 결과(전환점 발생·선택·반응·순간 포함)가 바이트 단위로 같다.
- 정의 하나를 추가해도 다른 정의의 발생 판정이 바뀌지 않음을 테스트(목적·code별 스트림).

### 테스트
- DSL: 정상 12+36+12 전부 검증 통과(시드 데이터 자체를 검증기로 로드), 잘못된 키·깊이 초과·범위 초과 거부
- 흐름: 전환점 발생 → advance 409 → 선택(성공·실패 각각, 확률 경계 0.1/0.9 고정 시드) → 효과 반영(현금 원장, 능력치, 보정 N분기 만료, shop_count) → advance 재개
- 멱등: 같은 키 선택 재요청 1회만 판정, 다른 엔드포인트 재사용 409, 동시 선택 2건 → 1건만
- 엔딩 분기에는 전환점 없음
- 반응: 2~3개, 실제 상호 없음(템플릿 치환 변수만), 같은 입력 같은 선택

### 완료 조건
1. `gradlew.bat clean check --offline` 통과 (테스트 수, 004A 골든 통과 명시)
2. 고정 시드 8분기 데모 결과 표(분기별 순이익, 전환점, 선택, 반응 개수)를 PROGRESS에
3. 정의 수 확인 쿼리: 전환점 12, 선택지 수, 반응 36, 순간 12

---

## 파트 2: 엔딩·기록·랭킹·공유 (V9)

### 할 것 (규범: `ENDING_DRAFT.md`)
1. **V9**: `drafts-004b/V9__endings_ranking.sql` 그대로(`game_records`, `ranking_snapshots`, `sell.*` 설정).
2. **엔딩 기록**: CLOSED·LONG_RUNNING(advance), SOLD(sell) 모든 경로가 **같은 트랜잭션에서** `game_records` 1행. `ranking_date`는 KST 날짜. `nickname`, 가게·사장님 이름, 업종·상권 코드/이름, `shop_count`는 엔딩 시점 스냅샷. `GameEndedEvent`는 커밋 후 부가 작업용.
3. **매각** `POST /api/games/{id}/sell`: `Idempotency-Key`(`endpoint='sell'`), 게임 행 잠금 + 본문 `expectedVersion` 검증, 영업 4분기 미만 400, 전환점 대기 중 409 `TURNING_POINT_PENDING`, 가치 = `max(0, 최근 4분기 평균 순이익) × sell.quarters`, 원장 `SALE`.
4. **요약** `GET /api/games/{id}/summary`: 소유자만, ENDED 아니면 409 `GAME_NOT_ENDED`, 원본은 `game_records`.
5. **랭킹 배치**: `@Scheduled(cron="0 0,30 * * * *", zone="Asia/Seoul")` + 시작 시 1회, `pg_try_advisory_xact_lock`으로 중복 실행 방지, 날짜별 DELETE → INSERT … SELECT, `RANK()` 공동 순위, 파티션별 상위 100, 00시대 실행이면 어제도 갱신.
6. **랭킹 API** `GET /api/rankings`: 최근 7일(밖이면 400), INDUSTRY/AREA는 key 필수, limit 기본 50 최대 100, entries에 `gameId` 추가. 관리자 `/admin/rankings`(마지막 갱신, 지금 갱신).
7. **공유** `GET /share/{gameId}`: **`web` 패키지**, 로그인 불필요, 없으면 404, OG 태그 `th:content`, 비공개 정보 없음. (TASK-005B가 이 컨트롤러에 인증글 링크를 붙인다.)

### 테스트
- 엔딩 3경로 각각 기록 1행, 같은 키 재시도 중복 없음, 매각 400/409/0원
- 랭킹: 공동 순위, 정렬 키 4단계, KST 자정 경계(23:50 / 00:10), 동시 배치 1개만, 7일 밖 400, key 누락 400
- 공유: 404, `<script>` 가게 이름 이스케이프, 비공개 정보 없음

### 완료 조건
1. `gradlew.bat clean check --offline` 통과
2. 데모: 게임 3개를 엔딩까지 → 배치 수동 실행 → `curl.exe -i` 랭킹(ALL, INDUSTRY), summary, 공유 HTML
3. TASK-005B용 대응표: `game_records` 컬럼 ↔ 게시판 RECORD 스냅샷 9필드

## 계약 변경 (메인이 API_CONTRACT 3장에 반영함)
`TURNING_POINT_PENDING`, `IDEMPOTENCY_KEY_REUSED`, `GAME_NOT_ENDED` 에러 코드, 선택 응답 version 규칙, 매각 최소 분기, 랭킹 entries `gameId`, 7일 범위.
