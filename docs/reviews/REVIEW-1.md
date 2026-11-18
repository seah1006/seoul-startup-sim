# 코드 리뷰 보고서 (REVIEW-1)

- 리뷰 대상: `main` (worktree `review/REVIEW-1`) + `origin/task/TASK-005A` (게시판 백엔드)
- 리뷰 범위: `AGENTS.md`, `README.md`, `docs/DESIGN.md`, `docs/api/API_CONTRACT.md`, `docs/data/DATA_NOTES.md`, `docs/benchmarks/BULK_LOAD.md`, `backend/`, `frontend/`
- 참고: `game` 패키지는 스타일 지적을 제외하고 로직 정확성·정합성·보안만 리뷰했다.

---

## 요약: 심각도별 건수

- **높음**: 5건
- **중간**: 9건
- **낮음**: 5건

---

## 1. 핵심 주장 검증 (포트폴리오·설계 주장이 코드로 성립하는가)

### [높음] 1-1. 데이터 버전 고정(`game_data_pins`)이 수집기 덮어쓰기로 무력화됨 (재현성·진행 중 게임 붕괴)
- **파일**:
  - `backend/src/main/resources/db/migration/V2__trade_areas.sql:9` (`trade_areas` PK `area_code`)
  - `backend/src/main/resources/db/migration/V3__area_quarterly.sql:4,19,32` (`area_sales_q`, `area_stores_q`, `area_floating_pop_q` PK)
  - `backend/src/main/resources/db/migration/V4__stores.sql:1` (`stores` PK `store_id`)
  - `backend/src/main/java/com/seah/startupsim/ingest/QuarterlyCollector.java:78-79`
  - `backend/src/main/java/com/seah/startupsim/catalog/store/StoreSnapshotService.java:21-22`
  - `backend/src/main/java/com/seah/startupsim/area/AreaRepository.java:36,47`
  - `backend/src/main/java/com/seah/startupsim/game/GameService.java:34,43,48,51`
  - `backend/src/main/java/com/seah/startupsim/game/AdvanceService.java:38-41`
- **문제**:
  핵심 주장 (4) "데이터 버전 고정으로 재현 가능한 게임"이 현재 스키마와 수집기 구조에서는 성립하지 않는다. 게임 생성 시 `game_data_pins`에 당시 `ACTIVE`인 `version_id`를 기록하고, `GameService.shop()`·`AdvanceService.advance()`는 `p.version_id = q.version_id` 조건으로 조회한다. 그러나 정작 참조 데이터 테이블의 PK에 `version_id`가 빠져 있어, 수집기가 같은 분기나 스냅샷을 다시 수집하면 기존 행의 `version_id`가 새 버전으로 덮어써지거나(`ON CONFLICT DO UPDATE`) 테이블 전체가 비워진다(`TRUNCATE stores`). 결과적으로 과거 버전 행이 사라져 **진행 중인 게임이 턴 진행 시 `400 VALIDATION_FAILED ("고정된 버전에 이 분기의 매출 추정 자료가 없습니다.")`로 영구 중단**되며, 과거 버전 기준 재현도 깨진다.
- **근거**:
  ```sql
  -- V3__area_quarterly.sql:19
  PRIMARY KEY (quarter, area_code, service_code) -- version_id 없음
  ```
  ```java
  // QuarterlyCollector.java:79
  + ") ON CONFLICT (" + keys + ") DO UPDATE SET " + updates + ",version_id=EXCLUDED.version_id";

  // StoreSnapshotService.java:22
  jdbc.execute("TRUNCATE stores");

  // AdvanceService.java:38
  var sales=r.jdbc().queryForList("SELECT q.sales_amount FROM area_sales_q q JOIN game_data_pins p ON p.version_id=q.version_id AND p.game_id=? WHERE q.area_code=? AND q.service_code=? AND q.quarter=?", ...);
  ```
- **고치는 방향**:
  1. 참조 데이터 테이블(`area_sales_q`, `area_stores_q`, `area_floating_pop_q`, `area_worker_pop_q`, `trade_areas`, `stores`)의 PK에 `version_id`를 포함해 버전별 불변(append-only) 스냅샷으로 저장하거나,
  2. `stores`처럼 행 수가 큰 테이블은 게임이 참조하는 과거 `version_id`를 보존·파티셔닝(예: `snapshot_version_id`)하고 `TRUNCATE` 대신 버전 단위 적재/아카이브로 전환해야 한다.

### [높음] 1-2. `QuarterCalculator`·`AdvanceService`의 `marketSize`와 `rivals` 조회 조건 불일치
- **파일**:
  - `backend/src/main/java/com/seah/startupsim/game/AdvanceService.java:38-44,50`
  - `backend/src/main/java/com/seah/startupsim/game/GameRepository.java:24` (`number(count, "store_count")`)
- **문제**:
  1. `AdvanceService.advance()`는 `area_stores_q`에 해당 상권·업종·분기 행이 없을 때 `count`를 `Map.of()`로 두고 `store_count`를 `0`으로 읽는다. 만약 `sales`도 없고(`market == null`), `average`(자치구 점포당 매출)는 있는데 `counts`가 비어 있으면 `marketSize = input.districtPerStore() * 0 = 0`이 된다.
  2. `AdvanceService.java:44`는 `SELECT count(*) FROM rivals WHERE game_id=?`로 폐업하지 않은 경쟁점만 세는 대신 `closed_turn IS NULL` 조건이 없다. (현재 `closed_turn`을 갱신하는 로직이 아직 없더라도, 추후 경쟁점 폐업 반영 시 과거 폐업 점포까지 경쟁 밀도에 포함될 잠재 결함이 있다.)
- **근거**:
  ```java
  // AdvanceService.java:40,50
  Map<String,Object> count=counts.isEmpty()?Map.of():counts.getFirst();
  ...
  long marketSize=market==null?input.districtPerStore()*input.stores():market;
  ```
- **고치는 방향**:
  `rivals` 카운트 시 `AND closed_turn IS NULL` 조건을 명시하고, `area_stores_q`가 누락된 분기에 대한 결측 정책(직전 분기 점포 수 fallback 또는 명시적 에러)을 `QuarterCalculator`와 맞춘다.

### [중간] 1-3. 수집 파이프라인: `@Scheduled` 미구현, `AreaMetaCollector`는 Resilience4j 우회, `SbizStoreCollector`는 로컬 CSV 전용
- **파일**:
  - `backend/src/main/java/com/seah/startupsim/StartupSimApplication.java` (`@EnableScheduling` 없음)
  - `backend/src/main/java/com/seah/startupsim/area/AreaMetaCollector.java:25,31,34-55`
  - `backend/src/main/java/com/seah/startupsim/catalog/store/SbizStoreCollector.java:22-27`
- **문제**:
  핵심 주장 (1) "외부 API 장애·제한 속에서도 멈추지 않는 수집 파이프라인"은 `QuarterlyCollector`·`WorkerPopulationCollector`가 쓰는 `SeoulOpenApiClient`에는 성립하지만, 다음 세 공백이 있다.
  1. `DESIGN.md` 13장과 `README.md`가 설명하는 `@Scheduled` 자동 수집 스케줄러가 코드에 없다(수동 실행·백필만 존재).
  2. `AreaMetaCollector`는 `SeoulOpenApiClient` 대신 자체 `RestClient`를 직접 생성해 사용하므로 RateLimiter·Retry·CircuitBreaker가 전혀 적용되지 않는다.
  3. `SbizStoreCollector`는 로컬 파일(`app.ingest.sbiz-csv`)만 읽으며 `DATA_GO_KR_SERVICE_KEY`를 쓰는 소상공인 API 클라이언트는 구현되어 있지 않다.
- **근거**:
  ```java
  // AreaMetaCollector.java:31
  client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  // SeoulOpenApiClient 미사용
  ```
- **고치는 방향**:
  `AreaMetaCollector`도 `SeoulOpenApiClient`를 경유하도록 통합하고, 포트폴리오/문서에서 현재 범위가 "수동·백필 수집 + 서울 API Resilience4j 적용"임을 명확히 구분한다.

### [중간] 1-4. 대량 적재(`CopyStoreBulkLoader`)의 배치당 스테이징 테이블 반복 생성·삭제 및 `TRUNCATE` 잠금
- **파일**:
  - `backend/src/main/java/com/seah/startupsim/catalog/store/CopyStoreBulkLoader.java:16-39`
  - `backend/src/main/java/com/seah/startupsim/catalog/store/StoreSnapshotService.java:21-22`
- **문제**:
  핵심 주장 (2) "대량 적재 성능"과 관련해 `BULK_LOAD.md`에도 일부 기록되어 있듯이, `CopyStoreBulkLoader`는 1,000행 배치마다 `CREATE UNLOGGED TABLE ...` -> `COPY` -> `INSERT ... ON CONFLICT` -> `DROP TABLE`을 553회 반복한다. 게다가 `StoreSnapshotService.replace()`가 시작 시 `TRUNCATE stores`를 실행해 `ACCESS EXCLUSIVE` 잠금을 약 50.8초간 유지하므로, 적재 중 상권 점포 조회(`CatalogService.stores`)와 창업(`GameService.shop`)이 모두 블로킹된다.
- **근거**:
  ```java
  // StoreSnapshotService.java:21-22
  jdbc.execute("CREATE TEMP TABLE store_previous ON COMMIT DROP AS TABLE stores");
  jdbc.execute("TRUNCATE stores");
  ```
- **고치는 방향**:
  파일 전체를 단일 `COPY` 스트림으로 영구/세션 스테이징 테이블에 적재한 뒤 한 번에 병합하고, 서비스 무중단이 필요하면 버전 컬럼 기반 파티션/뷰 스위칭을 검토한다.

### [중간] 1-5. `GameService.shop()`의 경쟁 점포 반경 조회에서 GiST 공간 인덱스 미사용
- **파일**: `backend/src/main/java/com/seah/startupsim/game/GameService.java:51`
- **문제**:
  핵심 주장 (3) "위치 기반 조회"에서 `CatalogService.stores`는 `s.geom && ST_Envelope(ST_Buffer(p.g::geography,?)::geometry)`로 GiST 인덱스를 태우지만(`BULK_LOAD.md` 근거), `GameService.shop()`은 `ST_DWithin(s.geom::geography, ..., 300)`만 사용해 `geom::geography` 캐스팅 때문에 `stores_geom_idx`(geometry 기반 GiST)를 활용하지 못한다(추정: `small_code` B-tree 후 전건 거리 계산).
- **근거**:
  ```sql
  -- GameService.java:51
  AND ST_DWithin(s.geom::geography,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,300)
  ```
- **고치는 방향**:
  `CatalogService.stores`와 동일하게 `s.geom && ST_Envelope(ST_Buffer(ST_SetSRID(ST_MakePoint(?,?),4326)::geography, 300)::geometry)` 조건을 추가한다.

### [낮음] 1-6. `AdvanceService`의 `Idempotency-Key` 교차 요청/파라미터 불일치 검증 부재
- **파일**: `backend/src/main/java/com/seah/startupsim/game/AdvanceService.java:25-27`
- **문제**:
  핵심 주장 (5) "턴 진행 정합성"에서 행 잠금(`FOR UPDATE`), 낙관적 버전(`expectedVersion`), DB 유일 제약(`turn_results`, `turn_decisions`), `idempotency_keys`는 잘 동작한다. 다만 같은 `game_id`에서 이미 완료된 이전 턴의 `Idempotency-Key`를 다른 `expectedVersion`과 함께 재전송해도 `cached`를 그대로 반환하므로, 클라이언트 버그로 키를 재사용했을 때 `409` 대신 과거 턴 응답이 내려간다.
- **고치는 방향**:
  `idempotency_keys`에 `request_hash` 또는 `turn`/`operation`을 함께 저장해 불일치 시 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.

---

## 2. 보안

### [높음] 2-1. `POST /api/guest` 및 게시판 레이트 리미터의 게스트 발급 우회 가능성
- **파일**:
  - `backend/src/main/java/com/seah/startupsim/game/GameController.java:28-29`
  - `backend/src/main/java/com/seah/startupsim/game/GuestAuth.java:16-20`
  - `origin/task/TASK-005A:backend/src/main/java/com/seah/startupsim/board/BoardRateLimiter.java:12-38`
- **문제**:
  1. `POST /api/guest`에 어떠한 레이트 리밋이나 봇 방어도 없어 무제한 `users` 행 생성이 가능하다.
  2. `BoardRateLimiter`는 `userId` 기준으로만 분당 글 3 / 댓글 10 / 신고 5회를 제한하고 단일 JVM 메모리 `HashMap`에 저장한다. 공격자가 `POST /api/guest`로 새 토큰을 반복 발급받으면 레이트 리밋을 100% 우회해 스팸 글·댓글·신고를 무한 작성할 수 있으며, 고정 창(fixed window) 특성상 분 경계(59초/00초)에서 2배 버스트도 허용된다.
- **근거**:
  ```java
  // GameController.java:28-29
  @PostMapping("/guest") @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> guest() { return auth.create(); }
  ```
  ```java
  // BoardRateLimiter.java:25-36
  long current = TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - start);
  if (current > window) { counters.clear(); window = current; }
  ```
- **고치는 방향**:
  `POST /api/guest`에 IP 기반 레이트 리밋을 적용하고, 게시판 레이트 리미터도 `userId` + IP 보조 제한 또는 Redis/DB 기반 슬라이딩 윈도우로 보완한다.

### [낮음] 2-2. `SecretMasker`에 `DB_PASSWORD` 마스킹 누락
- **파일**: `backend/src/main/java/com/seah/startupsim/common/SecretMasker.java:20-26`
- **문제**:
  `SecretMasker`가 `SEOUL_OPEN_API_KEY`, `DATA_GO_KR_SERVICE_KEY`, `ADMIN_PASSWORD`는 가리지만 `DB_PASSWORD`(`spring.datasource.password`)는 마스킹 목록에 없다. JDBC 예외 메시지에 접속 정보가 포함될 경우 `ApiExceptionHandler.unexpected` 스택 로그에 노출될 여지가 있다(추정).
- **고치는 방향**:
  `spring.datasource.password`도 마스킹 대상에 포함한다.

### 보안 양호 확인 항목
- **게스트 토큰**: `GuestAuth.java`에서 2개의 UUID를 합쳐 SHA-256 해시만 DB에 저장, 로그/에러 응답 미노출.
- **관리자 인증/CSRF**: `AdminSecurityConfig.java`에서 `/admin/**`에 `ROLE_ADMIN` 강제, `/api/**`만 CSRF 예외 처리하고 `/admin/**`·`/admin/api/**`는 CSRF 토큰 검증 유지.
- **SQL 인젝션**: `QuarterlyCollector`, `BoardPostRepository`, `BoardModerationRepository` 등 동적 SQL 부분은 enum 화이트리스트나 `Field` 설정으로만 조합하고, 사용자 검색어 `q`는 `\`, `%`, `_` 이스케이프 후 바인딩(`?`).
- **XSS**: `templates/admin/**`와 `origin/task/TASK-005A`의 `templates/admin/board/**` 전수 확인 결과 `th:utext` 사용 없음(모두 `th:text`, `th:value`, `th:action`). React 프론트도 `dangerouslySetInnerHTML` 미사용.

---

## 3. 동시성·트랜잭션

### [높음] 3-1. `QuarterlyCollector`가 HTTP 페이지 수집 전체를 단일 DB 트랜잭션으로 감싸 커넥션 고갈 위험
- **파일**:
  - `backend/src/main/java/com/seah/startupsim/ingest/QuarterlyCollector.java:66-99`
  - `backend/src/main/java/com/seah/startupsim/ingest/IngestRepository.java:64-65` (`Propagation.REQUIRES_NEW`)
- **문제**:
  `QuarterlyCollector.collect()`가 `transaction.execute(...)` 안에서 `client.pages(...)`를 호출한다.
  1. 외부 API 전체 페이지 호출(최대 78페이지+), `RateLimiter` 대기, `Retry` 지수 백오프(수십 초) 동안 **DB 커넥션 1개를 계속 점유**한다.
  2. 동시에 페이지 콜백 내부에서 `context.saveRaw(...)`를 호출하는데, `IngestRepository.saveRaw`는 `@Transactional(propagation = Propagation.REQUIRES_NEW)`이다. 즉 커넥션 1을 쥔 상태에서 매 페이지마다 커넥션 2를 추가로 요구하므로, 동시 수집이 겹치면 HikariCP 커넥션 풀(기본 10개) 고갈이나 자기 교착에 빠질 위험이 있다.
  `WorkerPopulationCollector.java:22-45`도 동일한 패턴을 사용한다.
- **근거**:
  ```java
  // QuarterlyCollector.java:66,80-81
  return transaction.execute(status -> {
      ...
      client.pages(service, quarter, page -> {
          context.saveRaw(service + ":" + quarter + ":" + page.start() + "-" + page.end(), page.raw());
  ```
- **고치는 방향**:
  외부 API 페이지 수집 및 `saveRaw`(`REQUIRES_NEW`)를 DB 트랜잭션 밖에서 먼저 완료하거나 스테이징에 모은 뒤, 적재·버전 전환 단계만 짧은 트랜잭션으로 묶는다.

### [중간] 3-2. `BoardPostService.authenticatedDetail()`이 단순 GET 조회마다 `posts` 행 배타 잠금(`FOR NO KEY UPDATE`) 획득
- **파일**:
  - `origin/task/TASK-005A:backend/src/main/java/com/seah/startupsim/board/BoardPostService.java:36-45`
  - `origin/task/TASK-005A:backend/src/main/java/com/seah/startupsim/board/BoardPostRepository.java:118-122`
- **문제**:
  인증된 사용자가 `GET /api/board/posts/{postId}`를 호출할 때마다 `posts.lockActive(postId)`가 `SELECT id FROM posts WHERE id = ? AND status = 'ACTIVE' FOR NO KEY UPDATE`를 실행한다. 같은 날 이미 조회한 사용자가 새로고침을 반복해도 매번 `posts` 행 잠금을 잡기 때문에, 인기 게시글에서 동시 조회·좋아요·댓글 쓰기가 모두 한 줄로 직렬화된다.
- **근거**:
  ```java
  // BoardPostService.java:39-43
  if (!posts.lockActive(postId)) throw BoardException.notFound();
  BoardWrites.safe(() -> {
      posts.countView(postId, userId, LocalDate.now(SEOUL));
      return null;
  });
  ```
- **고치는 방향**:
  조회수 중복 체크(`INSERT INTO post_views ... ON CONFLICT DO NOTHING`)를 먼저 실행해 실제 `inserted == 1`일 때만 `lockActive` + `view_count + 1`을 수행하거나, `UPDATE posts SET view_count = view_count + 1 WHERE id = ? AND status = 'ACTIVE'` 단일 원자적 UPDATE로 대체한다.

### [중간] 3-3. `AdminBoardController` 관리자 목록(`posts`, `reports`, `comments`)에 페이징·LIMIT 부재
- **파일**:
  - `origin/task/TASK-005A:backend/src/main/java/com/seah/startupsim/board/BoardModerationRepository.java:65-69,89-96,105-110`
- **문제**:
  관리자 게시글 목록(`posts`), 신고 목록(`reports`), 게시글 내 댓글 목록(`comments`) 쿼리에 `LIMIT`/`OFFSET`이 없다. 데이터가 누적되면 단일 관리자 화면 요청이 전체 테이블을 읽어 메모리·응답 지연을 유발한다.
- **고치는 방향**:
  `AdminController.runs`처럼 20~50건 단위 페이지네이션(`LIMIT 21 OFFSET ?`)을 추가한다.

### [낮음] 3-4. `BoardModerationRepository.change()`에서 숨김/삭제 시 `is_pinned` 미초기화
- **파일**:
  - `origin/task/TASK-005A:backend/src/main/java/com/seah/startupsim/board/BoardModerationRepository.java:121-126`
- **문제**:
  사용자 삭제(`BoardPostRepository.delete`)는 `is_pinned = false`로 초기화하지만, 관리자 숨김·삭제(`BoardModerationRepository.change`)는 `is_pinned`를 건드리지 않는다. 삭제 시에도 고정 플래그가 남아 있고, 숨김 후 복구(`RESTORE`)하면 이전 고정 상태가 의도치 않게 되살아난다.
- **고치는 방향**:
  관리자 `HIDDEN`/`DELETED` 전이 시에도 `is_pinned = false`를 함께 반영한다.

---

## 4. 데이터 정확성

### [중간] 4-1. `GameService.create()`에서 `game_settings` 키 누락 시 NPE 및 `AdvanceService`의 설정 캐스팅 취약성
- **파일**:
  - `backend/src/main/java/com/seah/startupsim/game/GameService.java:32`
  - `backend/src/main/java/com/seah/startupsim/game/AdvanceService.java:46-47`
- **문제**:
  `GameService.create()`는 `settings.get("initialCapital").longValue()`를 null 검사 없이 호출하고, `settings`를 읽을 때 `Double.parseDouble(rs.getString(2))`로 파싱한다. 추후 `game_settings`에 문자열·불리언 설정이 추가되거나 `initialCapital` 행이 없으면 게임 생성이 NPE/`NumberFormatException`으로 실패한다.
- **고치는 방향**:
  숫자 설정만 선택하거나 `getOrDefault`·안전한 JSON 숫자 파싱을 사용한다.

### [낮음] 4-2. `CatalogService.stores()`가 경로 변수 `areaCode`와 요청 좌표 `(lng, lat)`의 소속 상권 일치 여부를 검증하지 않음
- **파일**: `backend/src/main/java/com/seah/startupsim/catalog/CatalogService.java:50-63`
- **문제**:
  `GET /api/areas/{code}/stores?lng=...&lat=...`는 `areaName(code)`로 `code` 존재 여부만 확인하고 실제 공간 쿼리는 `code`와 무관하게 `(lng, lat)` 반경으로 조회한다. 임의의 `code`와 다른 지역 좌표를 조합해도 200 응답이 내려간다(설계상 허용이라면 문서화 필요).
- **고치는 방향**:
  의도된 동작이라면 `API_CONTRACT.md`에 명시하고, 상권 내부 좌표만 허용하려면 `ST_Contains` 검증을 추가한다.

### 데이터 정확성 양호 확인 항목
- **단위**: `area_sales_q.sales_amount`는 `bigint`로 분기 3개월 합계 그대로 저장·사용(`QuarterCalculator`). `area_floating_pop_q`는 단위 미확인으로 상대 지표로만 표기(`DATA_NOTES.md`, `V3`).
- **좌표계**: SHP(`EPSG:5181`) → `ST_Transform(..., 4326)` + `ST_MakeValid` + `ST_Multi(ST_CollectionExtract(..., 3))`로 무효 폴리곤 보정(`AreaRepository.java:28-33`).

---

## 5. 테스트 공백

### [중간] 5-1. 게임 동시성·재현성·수집 장애·게시판 핵심 테스트는 있으나 다음 경계 케이스 공백 존재
- **현황**: `GameIntegrationTest`(재현성·멱등성·동시성·소유권), `SeoulPipelineResilienceTest`(페이지 롤백·서킷 브레이커·레이트 리미터), `StoreIntegrationTest`(스냅샷 diff), `Board*IntegrationTest`(동시 대댓글 교착 회피·좋아요·신고·관리자 폼) 등 핵심 통합 테스트가 Testcontainers + WireMock으로 탄탄히 작성되어 있다.
- **공백 목록**:
  1. **버전 고정 vs 재수집 통합 테스트 부재**: 게임 생성(`game_data_pins`) 후 같은 분기/스냅샷을 재수집했을 때 기존 게임이 정상 진행되는지 검증하는 테스트가 없다(있었다면 1-1 결함이 즉시 드러났을 것).
  2. **`AreaMetaCollector` 실패·타임아웃 테스트**: `SeoulOpenApiClient` 미적용 상태에 대한 단위/통합 테스트 공백.
  3. **프론트엔드 실 API 계약 검증 테스트 부재**: `frontend/src/test/app.test.tsx`가 `mockRequest`만 테스트하고 있어, 아래 6장의 `api.search('')` 400 오류나 `floatingPopulation: null` 크래시를 잡지 못한다.
  4. **게시판 `BoardRateLimiter` 게스트 재발급 우회·경계 리셋** 및 **관리자 목록 필터 조합** 테스트 공백.

---

## 6. 프론트엔드 (계약 불일치·에러 처리)

### [높음] 6-1. `ShopSetup` 초기 마운트 시 `api.search('')` 호출로 실 백엔드에서 즉시 400 에러 발생
- **파일**:
  - `frontend/src/App.tsx:12`
  - `backend/src/main/java/com/seah/startupsim/area/AreaService.java:48`
- **문제**:
  `ShopSetup`의 `useEffect`가 마운트 직후 `api.search('')`를 호출한다. `mockRequest`는 빈 문자열이면 전체 연습 상권을 돌려주지만, 실제 백엔드 `AreaService.search`는 `if (q.isBlank() || q.length() > 100) throw new IllegalArgumentException(...)`로 **400 `VALIDATION_FAILED`를 반환**한다. 따라서 실제 API 모드(`VITE_USE_MOCK=false`)로 창업 화면에 진입하자마자 하단에 `"요청 값을 확인해 주세요."` 에러가 뜬다.
- **근거**:
  ```tsx
  // frontend/src/App.tsx:12
  useEffect(()=>{api.industries().then(setIndustries).catch(e=>setError(e.message));api.search('').then(setAreas).catch(e=>setError(e.message));},[]);
  ```
  ```java
  // AreaService.java:48
  if (q.isBlank() || q.length() > 100) throw new IllegalArgumentException("검색어는 1~100자여야 합니다.");
  ```
- **고치는 방향**:
  초기 마운트 시 `api.search('')`를 호출하지 말고 기본 검색어(또는 추천 상권/지도 bbox 조회)를 사용하거나, 프론트에서 빈 검색어 제출을 막는다.

### [중간] 6-2. `ShopSetup`에서 `report.floatingPopulation`이 `null`일 때 런타임 크래시 (`TypeError`)
- **파일**:
  - `frontend/src/App.tsx:16`
  - `frontend/src/api/types.ts:13`
  - `backend/src/main/java/com/seah/startupsim/catalog/CatalogService.java:47`
- **문제**:
  백엔드 `CatalogService.report()`는 유동인구 행이 없으면 `floatingPopulation: null`을 반환하고, `openingRatePct`/`closingRatePct`도 `null`일 수 있다. 프론트 `types.ts`는 이를 `number`로만 선언했고 `App.tsx`는 `{report.floatingPopulation.toLocaleString()}`를 null 체크 없이 호출해 해당 상권 선택 시 화면이 크래시된다.
- **근거**:
  ```tsx
  // frontend/src/App.tsx:16
  {report&&<><p>길단위인구 <b>{report.floatingPopulation.toLocaleString()}</b></p>
  ```
- **고치는 방향**:
  `types.ts`에서 `floatingPopulation: number | null`, `openingRatePct: number | null`, `closingRatePct: number | null`로 수정하고 null일 때 `"자료 없음"`으로 표시한다.

### [중간] 6-3. `client.ts`에서 `401 UNAUTHORIZED` 응답 시 만료/무효 게스트 토큰 복구 불가
- **파일**: `frontend/src/api/client.ts:6-7`
- **문제**:
  `guest()`가 `localStorage`의 `sss-guest` 토큰을 무조건 재사용한다. DB 초기화나 토큰 무효화로 `401`이 반환돼도 `localStorage`를 비우거나 재발급하지 않으므로 사용자가 직접 스토리지를 지우기 전까지 모든 요청이 `401`로 실패한다.
- **고치는 방향**:
  `401` 응답 시 `localStorage.removeItem(storageKey)` 후 1회 새 게스트 토큰을 발급받아 재시도(또는 안내)한다.

### [낮음] 6-4. `Game` 엔딩 화면이 `CLOSED`(파산)와 `LONG_RUNNING`(장수 가게)를 구분하지 않음
- **파일**: `frontend/src/App.tsx:21`
- **문제**:
  `game.status === 'ENDED'`일 때 `endingType` 확인 없이 무조건 `"오래 사랑받는 가게가 되었어요"`를 출력한다. 파산(`CLOSED`) 엔딩에도 축하 문구가 노출된다.
- **고치는 방향**:
  `game.endingType === 'CLOSED'`일 때는 폐업 엔딩 문구를 분기해 표시한다.

---

## 7. 면접관이 물어볼 만한 약점 질문 5개 & 답변 가능 여부

1. **"게임 생성 시 `game_data_pins`로 데이터 버전을 고정했다고 했는데, 실제로 새 분기/스냅샷을 수집하면 `ON CONFLICT DO UPDATE`와 `TRUNCATE stores`로 과거 `version_id` 행이 덮어써지거나 지워지지 않나요? 진행 중인 게임이 깨지지 않나요?"**
   - **현재 답변 가능 여부**: **방어 불가 (코드 결함 확인됨)**. PK에 `version_id`가 없어 재수집 시 기존 게임이 깨진다. 리뷰 1-1 조치 후 "버전별 불변 스냅샷으로 분리했다"고 답할 수 있어야 한다.

2. **"`QuarterlyCollector`가 외부 API 전체 페이지를 하나의 DB 트랜잭션 안에서 돌리면서 페이지마다 `REQUIRES_NEW`로 `raw_payloads`를 저장하는데, API가 느려지거나 동시 수집이 돌면 DB 커넥션 풀이 고갈되지 않나요?"**
   - **현재 답변 가능 여부**: **부분 답변만 가능 (설계 의도 설명은 가능하나 구조적 약점)**. "원본 보존을 위해 `REQUIRES_NEW`를 썼다"고 설명할 수 있지만, HTTP 호출 중 외곽 트랜잭션 커넥션을 잡고 있는 점은 개선이 필요하다(리뷰 3-1).

3. **"`POST /api/guest`에 아무 제한이 없는데, 누군가 게스트 토큰을 무한 발급받아 `BoardRateLimiter`(userId 기반)를 우회해 게시판을 도배하면 어떻게 막나요?"**
   - **현재 답변 가능 여부**: **방어 불가**. 현재는 단일 JVM 메모리 `userId` 카운터만 있어 토큰 재발급 우회와 다중 인스턴스 분산 환경에 취약하다(리뷰 2-1).

4. **"`GET /api/board/posts/{postId}`처럼 단순 조회 API에서 `FOR NO KEY UPDATE` 행 잠금을 거는데, 인기 글에 트래픽이 몰리면 조회 요청끼리 DB 락 경합으로 병목이 생기지 않나요?"**
   - **현재 답변 가능 여부**: **의도는 설명 가능하나 성능 약점**. "조회 중 관리자 숨김과의 경합으로 비공개 글 조회수가 올라가는 걸 막기 위함"이라고 의도는 설명할 수 있으나, 이미 조회한 유저까지 매번 행 잠금을 잡는 것은 과도하다(리뷰 3-2).

5. **"프론트엔드와 백엔드가 실제로 잘 붙는지 검증했나요? `ShopSetup` 진입 시 `api.search('')`가 400을 내고 `floatingPopulation`이 null일 때 터지는 걸 못 잡은 이유는 뭔가요?"**
   - **현재 답변 가능 여부**: **부분 답변 가능**. "프론트 테스트가 `mockRequest` 기반으로만 작성돼 실 백엔드 계약과의 불일치를 놓쳤다"고 솔직히 인정하고(리뷰 6-1, 6-2), 통합 E2E 또는 계약 테스트 보완 계획을 제시해야 한다.
