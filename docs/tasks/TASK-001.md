# TASK-001: 백엔드 뼈대 + DB + 수집 이력 프레임워크

- 브랜치: `task/TASK-001`
- 담당: 구현 에이전트 (Codex)
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` (특히 11장 ERD 중 "수집 기록", 13장 수집 파이프라인)

## 목표
아무 기능 없는 빈 껍데기가 아니라, **"수집 실행을 기록하는 프레임워크"까지 동작하는 백엔드 뼈대**를 만든다. 실제 외부 API 수집기는 TASK-002 이후에 만든다(별도 에이전트가 지금 실제 데이터 형식을 조사 중).

## 만들 것

### 1. 프로젝트 구조
- `backend/`에 Spring Boot 프로젝트 (Gradle Groovy DSL, Gradle Wrapper 포함)
- Java 25 툴체인. Spring Boot는 Java 25를 공식 지원하는 최신 안정 버전을 고르고, 고른 버전과 이유를 보고에 적는다.
- 의존성: web, thymeleaf, data-jpa, jdbc, validation, actuator, flyway(+ flyway-database-postgresql), postgresql 드라이버, hibernate-spatial(PostGIS 타입용), lombok은 쓰지 않는다(수업용 코드라 명시적인 코드 선호), 테스트: spring-boot-starter-test, testcontainers(postgresql, junit-jupiter)
- 패키지 루트 `com.seah.startupsim`, 하위: `common`, `ingest`

### 2. 로컬 DB
- 루트에 `docker-compose.yml`: PostGIS 공식 이미지(PostgreSQL 16 이상 + PostGIS 3.4 이상), DB 이름 `seoul_startup_sim`, 계정과 비밀번호는 루트 `.env`의 `DB_USERNAME`, `DB_PASSWORD`를 쓰도록 (compose의 env_file 또는 변수 치환). 볼륨 이름 지정.
- `backend/src/main/resources/application.yml`: DB 접속은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수. 루트 `.env`를 자동으로 읽는 방법(예: `spring.config.import=optional:file:../.env[.properties]`)을 적용하고, 동작을 확인한다. JPA `ddl-auto`는 `validate`.
- 외부 API 키 설정 자리만 만든다: `app.external.seoul.api-key=${SEOUL_OPEN_API_KEY:}`, `app.external.datagokr.service-key=${DATA_GO_KR_SERVICE_KEY:}` 를 `@ConfigurationProperties` 클래스로 바인딩. 키 값을 로그에 찍지 않는다.

### 3. Flyway 마이그레이션 `V1__init.sql`
- `CREATE EXTENSION IF NOT EXISTS postgis;`
- `data_versions (id bigserial PK, source varchar(50) not null, period varchar(20), status varchar(20) not null check (status in ('LOADING','READY','ACTIVE','FAILED')), row_count bigint, created_at timestamptz not null default now(), activated_at timestamptz)`
  - 같은 `source`에 `ACTIVE`는 하나만: 부분 유니크 인덱스 `WHERE status = 'ACTIVE'`
- `ingest_runs (id bigserial PK, source varchar(50) not null, target_period varchar(20), status varchar(20) not null check (status in ('RUNNING','SUCCEEDED','FAILED')), fetched int not null default 0, inserted int not null default 0, updated int not null default 0, failed int not null default 0, error_message text, started_at timestamptz not null default now(), finished_at timestamptz)`
  - 인덱스: `(source, started_at desc)`
- `raw_payloads (id bigserial PK, run_id bigint not null references ingest_runs(id), source varchar(50) not null, request_key varchar(500) not null, payload jsonb not null, checksum char(64) not null, fetched_at timestamptz not null default now())`
  - 같은 실행 안에서 같은 요청 중복 금지: `unique (run_id, request_key)`

### 4. 수집 프레임워크 (`ingest` 패키지)
- `Collector` 인터페이스: `String source()`, `CollectResult collect(IngestContext ctx)` 정도의 단순한 형태. `IngestContext`로 run id, 대상 기간, 원본 저장 메서드(`saveRaw(requestKey, jsonPayload)`)를 넘긴다.
- `IngestService.run(String source, String targetPeriod)`:
  1. `ingest_runs`에 RUNNING 행 생성 (별도 트랜잭션으로 먼저 커밋해서 실패해도 기록이 남게)
  2. 해당 `source`의 `Collector` 실행
  3. 성공하면 SUCCEEDED + 건수 + finished_at, 예외면 FAILED + 에러 메시지(최대 길이 제한) + finished_at
  4. 같은 `source`가 이미 RUNNING이면 새로 시작하지 않고 거절 (동시 실행 방지). 방법은 자유이되 이유를 보고에 적는다 (예: Postgres advisory lock, 조건부 insert)
- 원본 저장 시 checksum은 payload의 SHA-256 hex
- 등록되지 않은 source로 실행하면 명확한 예외
- 테스트용으로만 쓰는 가짜 Collector는 `src/test`에 둔다. `src/main`에 가짜 수집기를 넣지 않는다.

### 5. 공통
- `common`: 표준 에러 응답 `{ "code": "...", "message": "...", "timestamp": "..." }`와 `@RestControllerAdvice`
- `GET /api/health/db`: DB 연결과 PostGIS 버전(`SELECT postgis_version()`) 반환. 간단한 확인용.

### 6. 테스트
- Testcontainers(PostGIS 이미지, compose와 같은 버전)로:
  - Flyway 마이그레이션 성공, PostGIS 확장 존재
  - 가짜 Collector 성공 시: SUCCEEDED, 건수, raw_payloads 저장, checksum 정확
  - 가짜 Collector 예외 시: FAILED와 에러 메시지가 남음 (롤백돼도 run 기록은 남아야 함)
  - 같은 source 동시 실행 2개 → 하나만 실행됨
  - `data_versions`에 같은 source ACTIVE 두 개 넣으면 DB가 거절
- `/api/health/db` 웹 테스트 1개

### 7. 문서
- `README.md`의 "진행 상황"은 건드리지 않는다(메인 에이전트가 갱신).
- `backend/README.md`에 실행 방법(compose up, bootRun, check)을 짧게.

## 하지 않을 것
- 실제 외부 API 호출, 상권/매출 테이블, 게임 테이블, 프론트엔드, 보안 설정, 카카오 로그인
- Resilience4j는 TASK-002에서 넣는다.

## 완료 조건 (직접 실행하고 결과를 보고)
1. `docker compose up -d` 후 `cd backend && gradlew.bat clean check` 통과 (테스트 개수 보고)
2. `gradlew.bat bootRun` 실행 → `curl http://localhost:8080/api/health/db` 응답 확인 → 종료
3. `git status`에 `.env`나 빌드 산출물이 없음
4. `task/TASK-001` 브랜치에 의미 단위로 나눈 커밋 (push 하지 않음)

## 보고 형식
- 만든 파일 목록(요약), 선택한 버전과 이유, 동시 실행 방지 방법과 이유
- 완료 조건 각각의 실제 결과
- 남은 문제, 제안
