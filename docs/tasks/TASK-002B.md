# TASK-002B: 서울 Open API 클라이언트(안정성) + 분기 데이터 수집기

- 브랜치: `task/TASK-002B` (worktree `C:\Users\my\dev\sss-002b`)
- 담당: 구현 에이전트 (Codex)
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 9장·13장, `docs/data/DATA_NOTES.md` (A-0~A-2, A-6, 권고 1·2)
- **Flyway 버전: `V3__area_quarterly.sql` 만 사용**
- 주의: `trade_areas`, `service_industries` 테이블은 TASK-002A가 V2에서 만든다. 이 작업은 **FK를 걸지 않고** 코드 컬럼만 둔다(병합 후 별도 작업에서 FK 추가).

## 목표
이 프로젝트 백엔드의 핵심 이야기 1번 "외부 API 장애와 제한 속에서도 멈추지 않는 수집 파이프라인"을 만든다.

## 만들 것

### 1. 공통 외부 API 클라이언트 (`ingest.client`)
- `SeoulOpenApiClient`: `http://openapi.seoul.go.kr:8088/{KEY}/json/{service}/{start}/{end}/{args...}` 호출, 1,000건 단위 페이지 반복, `list_total_count`로 끝 판단
- 서울 API는 오류도 HTTP 200 + `RESULT.CODE`로 준다(예: `INFO-000` 정상, `INFO-200` 데이터 없음, `ERROR-xxx`). 코드별로 예외 타입을 나눈다: 데이터 없음(정상 0건), 재시도 가능(일시 오류, 5xx, 타임아웃), 재시도 불가(인증 오류 등)
- Resilience4j(Spring Boot 버전에 맞는 모듈): 
  - RateLimiter: 초당 N건 (설정값, 기본 5)
  - Retry: 재시도 가능 오류만, 지수 백오프(기본 3회, 0.5s→1s→2s)
  - CircuitBreaker: 연속 실패 시 열림. 열린 상태면 수집 실행은 즉시 FAILED로 기록(에러 메시지에 "circuit open")
- 타임아웃(연결 3s, 읽기 10s, 설정값)
- 요청/재시도/실패 로그에 키가 나오지 않게 `SecretMasker` 사용
- 이 클라이언트는 나중에 다른 출처에서도 쓰이므로 설정 이름을 `app.external.seoul.*`로 분리

### 2. `V3__area_quarterly.sql`
DATA_NOTES 권고 2를 따른다. 모두 `(quarter, area_code, service_code)` 또는 `(quarter, area_code)` 복합 PK, `version_id bigint references data_versions(id)`.
- `area_sales_q`: 분기 매출 금액(bigint, **분기 3개월 합계**라고 주석), 매출 건수, 주중/주말, 시간대·성별·연령대 컬럼 중 게임에 쓸 핵심만(필요 판단 근거를 보고). 원본 전체는 raw_payloads에 있으니 다 펼칠 필요 없음
- `area_stores_q`: 점포 수(SIMILR_INDUTY_STOR_CO), 일반, 프랜차이즈, 개업률(정수 %), 개업 수, 폐업률, 폐업 수
- `area_floating_pop_q`: 총 유동인구, 남/여, 시간대 핵심(단위 미확인이라 컬럼 주석에 "단위 미확인(DATA_NOTES)" 표기)
- quarter는 `char(5)` (예: `20262`) + check 형식

### 3. 수집기 3개 (`Collector`)
- `seoul-area-sales` (VwsmTrdarSelngQq), `seoul-area-stores` (VwsmTrdarStorQq), `seoul-area-floating-pop` (VwsmTrdarFlpopQq)
- `targetPeriod` = 분기 코드. 페이지마다 원본을 raw_payloads에 저장(request_key = 서비스명+분기+페이지 범위)
- 적재: JdbcTemplate `batchUpdate` + `ON CONFLICT DO UPDATE`. 한 분기 전체를 한 트랜잭션으로(중간 실패 시 그 분기 데이터는 이전 상태 유지)
- 성공하면 `data_versions` (source, period=분기) READY 후 ACTIVE 전환 — 분기별 데이터는 source+period 단위로 버전 관리하므로, ACTIVE 유일성 인덱스가 source 단위인 V1과 맞지 않으면 V3에서 인덱스를 `(source, coalesce(period,''))` 기준으로 바꾸고 이유를 주석과 보고에 적는다
- 결과 건수(fetched/inserted/updated) 정확히

### 4. 여러 분기 한꺼번에
- `IngestService`에 손대지 말고, 별도 `QuarterBackfillService.backfill(source, fromQuarter, toQuarter)`: 분기를 순서대로 실행, 하나 실패해도 다음 분기 계속, 결과 요약 반환
- `POST /admin/api/backfill/{source}?from=20211&to=20262` (임시, `app.admin.enabled=true`일 때만)

### 5. 테스트 (WireMock, `docs/data/samples` 응답 활용)
- 정상 2페이지 수집 → 행 수, raw_payloads 수, data_versions
- `INFO-200` → 0건 SUCCEEDED
- 5xx 2번 후 성공 → 재시도 동작, 최종 SUCCEEDED
- 인증 오류 → 재시도 없이 FAILED, 에러 메시지에 키 없음
- 연속 실패 → CircuitBreaker 열림 → 다음 실행 즉시 FAILED("circuit open")
- RateLimiter: 설정 초당 2건으로 5페이지 → 최소 소요 시간 검증(느슨하게)
- 같은 분기 2회 수집 → 행 수 동일(멱등)
- 2페이지째 실패 → 그 분기 데이터 롤백, run은 FAILED로 남음

## 하지 않을 것
상권 영역(002A), 상가업소(002C), 직장인구(다음 작업), 관리자 화면(Thymeleaf), 보안

## 완료 조건 (직접 실행해서 보고)
1. `gradlew.bat clean check` 통과 (테스트 수)
2. 실제 API로 로컬 적재: `20262` 분기 3개 수집기 실행 → 각 테이블 행 수, 소요 시간. 매출 행 수가 DATA_NOTES의 값과 일치하는지
3. 백필 `20251~20262` 1개 수집기(매출)만 실행 → 분기별 결과
4. 키가 커밋/로그에 없음 (`git grep`, 실행 로그 검색)

## 보고 형식
Resilience4j 설정값과 이유, ACTIVE 인덱스 변경 여부, 완료 조건 실제 결과, 남은 문제
