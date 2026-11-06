# TASK-002C: 상가업소 55만 건 대량 적재 + 적재 방식 성능 비교

- 브랜치: `task/TASK-002C` (worktree `C:\Users\my\dev\sss-002c`)
- 담당: 구현 에이전트 (Codex)
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 9~11장, `docs/data/DATA_NOTES.md` (B-1~B-5, 권고 1·2)
- **Flyway 버전: `V4__stores.sql` 만 사용**
- 주의: `service_industries`(002A), 분기 테이블(002B)과 FK를 걸지 않는다.

## 목표
포트폴리오 핵심 이야기 2번 "대량 적재"를 수치로 증명한다. 서울 상가업소 552,422건을 적재하면서 세 가지 방식의 성능을 같은 조건에서 비교하고 문서로 남긴다.

## 입력
- 서울 CSV: 메인 저장소 `C:\Users\my\dev\seoul-startup-sim\data\raw\sbiz\seoul.csv` (UTF-8, 약 287MB, gitignore 대상). 경로는 설정값 `app.ingest.sbiz-csv`.
- 컬럼 구조와 의미는 DATA_NOTES B장과 `docs/data/samples`의 상가업소 샘플 참고

## 만들 것
1. `V4__stores.sql`
   - `stores (store_id varchar(30) PK /* 상가업소번호 */, name, branch_name, large_code, middle_code, small_code, small_name, std_industry_code, district_code, dong_code, road_address, geom geometry(Point,4326) not null, snapshot varchar(20) not null /* 데이터 기준 시점 */, version_id bigint references data_versions(id))`
   - GiST(geom), (small_code), (dong_code) 인덱스
   - 스냅샷 비교용 `store_snapshot_diffs (snapshot, store_id, change_type check in ('NEW','CLOSED','CHANGED'))` — 이번 작업에선 테이블과 계산 로직만, 실제 두 번째 스냅샷은 아직 없음(테스트로만 검증)
2. CSV 파서: 스트리밍(전체를 메모리에 올리지 않음). 라이브러리는 Apache Commons CSV 또는 직접 구현 중 택1, 이유 보고. 좌표는 경도/위도 WGS84(DATA_NOTES B-5 확인).
3. 적재 전략 3가지를 같은 인터페이스(`StoreBulkLoader`)로 구현
   - (a) JPA `saveAll` (엔티티 + batch_size 설정, 공정한 비교를 위해 합리적인 최적화는 적용: `hibernate.jdbc.batch_size`, order_inserts. 단 ID가 문자열 PK라 IDENTITY 문제 없음)
   - (b) JdbcTemplate `batchUpdate` (배치 크기 1,000, `reWriteBatchedInserts=true` 적용 여부를 각각 측정하면 더 좋음)
   - (c) PostgreSQL `COPY` (`CopyManager`) → 스테이징 테이블(UNLOGGED) → `INSERT ... SELECT ... ON CONFLICT DO UPDATE` 로 본 테이블 반영 (geom은 `ST_SetSRID(ST_MakePoint(lng,lat),4326)`)
   - 기본 운영 수집기 `sbiz-stores-csv`(`Collector`)는 가장 빠른 방식을 쓴다. raw_payloads에는 파일 메타(파일명, 크기, SHA-256, 행 수)만 저장.
4. 스냅샷 비교 로직: 새 스냅샷 적재 시 이전 스냅샷과 비교해 NEW/CLOSED/CHANGED를 `store_snapshot_diffs`에 기록 (SQL로 집합 연산). 테스트에서 작은 두 스냅샷으로 검증.
5. 벤치마크
   - 별도 Gradle 태스크 또는 `@Tag("benchmark")` 테스트로 분리해서 `check`에는 포함하지 않는다.
   - 같은 DB(로컬 compose, 5433), 매 측정 전 테이블 TRUNCATE, 웜업 1회 후 3회 측정 중앙값
   - 측정 규모: 1만, 10만, 전체(55만). (a)가 전체에서 너무 오래 걸리면(>15분) 10만까지만 하고 그 사실을 기록
   - 결과를 `docs/benchmarks/BULK_LOAD.md`에 표로: 방식, 건수, 소요 시간, 초당 건수, 메모리 최대치(가능하면), 측정 환경(CPU, RAM, Docker 버전, JDK). 해석과 "운영에서 COPY+스테이징을 고른 이유" 3~5줄
6. 테스트(`check`에 포함): 작은 CSV(20행, 따옴표·쉼표·빈 값 포함)로 세 방식 모두 같은 결과(행 수, 특정 행 값, 좌표), 멱등성(2회), 잘못된 좌표 행 처리 정책(건너뛰고 failed 카운트)

## 하지 않을 것
업종 매핑 적용, 반경 조회 API(다음 작업), Open API 기반 갱신(다음 작업), 프론트엔드

## 완료 조건 (직접 실행해서 보고)
1. `gradlew.bat clean check` 통과 (테스트 수)
2. 벤치마크 실행 결과 표 (`docs/benchmarks/BULK_LOAD.md`)
3. 운영 수집기로 실제 전체 적재 → `select count(*) from stores` = CSV 데이터 행 수(불일치 시 이유), 소요 시간
4. 반경 질의 성능 맛보기: 망원동 근처 점(126.905, 37.556) 반경 300m 같은 소분류 업소 수 쿼리의 `EXPLAIN ANALYZE` 실행 시간 (GiST 사용 여부)
5. 키가 커밋에 없음

## 보고 형식
선택한 라이브러리와 이유, 벤치마크 표 요약, 완료 조건 실제 결과, 남은 문제
