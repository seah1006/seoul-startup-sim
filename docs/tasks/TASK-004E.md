# TASK-004E: 데이터 버전 보존(재현성 복구) + 수집 트랜잭션 분리 + 리뷰 보안 지적

- 브랜치: `task/TASK-004E` (worktree `C:\Users\my\dev\sss-004e`, DB 5440, 앱 8088)
- 담당: Codex
- **착수 게이트**: TASK-004A가 main에 병합된 뒤(게임의 분기 데이터 조회가 `MarketDataRepository`로 모였다)
- 선행: `AGENTS.md`, `docs/DESIGN.md`(9~13장), `docs/data/DATA_NOTES.md`, 리뷰 `git show review/REVIEW-1:docs/reviews/REVIEW-1.md` (1-1, 1-3, 2-1, 2-2, 3-1, 5-1)
- **Flyway: `V7_1__versioned_reference_data.sql` 만 사용.** V8·V9(TASK-004B), V10(004D), V11(005A)보다 앞 번호라 지금 main(V7) 위에 바로 순서대로 들어간다. `outOfOrder=false` 유지.
- 동시에 TASK-004B 파트 1(V8, game/event 패키지)이 진행될 수 있다. **game 패키지는 `MarketDataRepository`와 게임 생성 시 버전 고정 부분만** 수정 가능. 나머지 game 코드는 건드리지 않는다.

## 1. 데이터 버전 보존 [리뷰 1-1, 높음]
### 문제
`game_data_pins`로 게임마다 데이터 버전을 고정하지만, 분기 테이블의 PK에 `version_id`가 없어 같은 분기를 다시 수집하면 `ON CONFLICT DO UPDATE`로 기존 행의 `version_id`가 새 값으로 바뀐다. 그 순간 진행 중인 게임은 고정 버전의 행을 찾지 못해 다음 분기로 넘어갈 수 없다. "재현 가능한 게임"이라는 핵심 주장이 깨진다.

### 할 것
1. 분기 참조 테이블을 **버전별 불변(append-only)** 으로: `area_sales_q`, `area_stores_q`, `area_floating_pop_q`, `area_worker_pop_q`의 PK를 `(version_id, quarter, area_code[, service_code])`로. 기존 데이터는 마이그레이션에서 그대로 보존(현재 행의 version_id 유지).
   - 조회 성능: 활성 버전 조회용 인덱스 `(quarter, area_code, service_code)` 유지/추가
2. 수집기: 같은 (source, period)를 다시 수집하면 **새 data_version에 새 행을 INSERT**, 성공 시 ACTIVE 전환(이전 ACTIVE는 READY). 기존 행은 건드리지 않는다.
3. 읽기 쪽:
   - 카탈로그(상권 리포트 등): 해당 (source, period)의 **ACTIVE 버전**만 읽는다(뷰 `*_active` 또는 쿼리에 data_versions 조인).
   - 게임: 고정 버전만 읽는다(이미 그렇다면 그대로). 게임 생성 시 "그 순간의 모든 (source, period) ACTIVE 버전"을 고정하는지 확인하고, 아니면 고친다.
4. 보존 정책: ACTIVE도 아니고 어떤 게임에도 고정되지 않은 버전을 지우는 정리 작업(`DataVersionPurgeService`) + 관리자 화면 `/admin/data-versions`에 "정리" 버튼과 삭제 대상 미리보기. 고정된 버전은 절대 삭제 안 됨(DB에서도: `game_data_pins.version_id` FK가 `ON DELETE RESTRICT`인지 확인).
5. 상가업소 `stores`와 상권 `trade_areas`는 게임이 창업 시점에 필요한 값을 복사(rivals, shops)하므로 버전 보존 대상에서 제외한다. 이 결정과 근거를 `DESIGN.md` 9장과 16장(결정 기록)에 적는다.
6. **회귀 테스트 (가장 중요)**: 게임 생성 → 2분기 진행 → 같은 분기 재수집(새 버전 ACTIVE) → 게임이 계속 진행되고, 같은 시드·결정의 다른 게임(재수집 전 생성)과 결과가 바이트 동일. 카탈로그 리포트는 새 버전 값을 반환.

## 2. 수집 트랜잭션 분리 [리뷰 3-1, 높음]
`QuarterlyCollector`, `WorkerPopulationCollector`가 외부 API 전체 페이지 호출을 하나의 DB 트랜잭션 안에서 하고, 페이지마다 `REQUIRES_NEW`로 원본을 저장해 커넥션을 2개씩 잡는다.
- 단계 분리: (1) 트랜잭션 없이 페이지 수집 + 페이지별 원본 저장(짧은 개별 트랜잭션) → (2) 수집이 모두 성공하면 짧은 트랜잭션 하나에서 새 버전 행 일괄 INSERT + 버전 전환. 중간 실패면 (2)를 하지 않으므로 롤백 보장은 그대로.
- 메모리: 한 분기 최대 약 8만 행. 행 객체를 모아도 되지만, 원본을 raw_payloads에서 다시 읽어 적재하는 방식도 가능. 선택과 이유를 보고.
- 테스트: HikariCP 최대 커넥션 2로 설정한 테스트에서 느린 API(WireMock 지연) 수집 중에도 다른 조회가 커넥션을 얻는다. 기존 `SeoulPipelineResilienceTest`(중간 실패 롤백, 서킷 브레이커, 속도 제한)는 그대로 통과.

## 3. 수집 일관성 [리뷰 1-3, 중간]
- `AreaMetaCollector`가 자체 `RestClient`를 써서 속도 제한·재시도·서킷 브레이커를 우회한다 → `SeoulOpenApiClient` 경유. WireMock 장애 테스트 1개.
- `@Scheduled` 자동 수집: `@EnableScheduling` + 분기 데이터 갱신 확인 잡(매일 1회, 설정으로 끄고 켬, 기본 꺼짐). 최신 분기 이후 분기가 새로 나왔는지 확인해 있으면 백필. 동시 실행은 기존 RUNNING 유일 인덱스로 막힌다. README의 수집 설명을 실제와 맞춘다.

## 4. 보안 [리뷰 2-1, 2-2]
- `POST /api/guest`에 IP 기준 발급 제한(예: IP당 10분에 20개, 설정값). `common` 패키지의 서블릿 필터로 구현해 game 코드 수정 없이. 초과 시 429 `RATE_LIMITED`(공통 에러 형식). 프록시 뒤 IP(X-Forwarded-For)는 `app.security.trust-proxy=true`일 때만.
- `SecretMasker`에 `spring.datasource.password`, `ADMIN_PASSWORD`, 카카오 시크릿 추가. 테스트.

## 완료 조건
1. `gradlew.bat clean check --offline` 통과 (테스트 수)
2. 회귀 테스트(1-6) 통과 로그
3. **메인 DB(5433) 마이그레이션 리허설**: 메인 DB를 직접 건드리지 말고, `pg_dump`로 메인 DB를 너의 DB(5440)에 복원한 뒤 V7_1을 적용해 소요 시간·행 수 보존을 확인하고 보고(복원 명령은 `docker exec`로 컨테이너 안에서). 메인 DB 적용은 메인 에이전트가 한다.
4. 문서: DESIGN 9·13·16장, README 수집 설명 갱신
