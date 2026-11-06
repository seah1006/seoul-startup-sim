# TASK-002A: 상권 영역(폴리곤) + 서비스 업종 적재

- 브랜치: `task/TASK-002A` (worktree `C:\Users\my\dev\sss-002a`)
- 담당: 구현 에이전트 (Codex)
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 9~11장, `docs/data/DATA_NOTES.md` (A-0, A-1, A-3, A-4, A-5, 권고 1·2·4)
- **Flyway 버전: `V2__trade_areas.sql` 만 사용** (V3, V4는 다른 작업이 씀)

## 목표
서울 상권 1,650개를 폴리곤과 함께 DB에 넣고, 지도에서 쓸 수 있게 조회 API까지 만든다.

## 입력 데이터
- SHP: 메인 저장소 `C:\Users\my\dev\seoul-startup-sim\data\raw\area\trdar.(shp|shx|dbf|prj|cpg)` (EPSG:5181, gitignore 대상). 경로는 설정값 `app.ingest.area-shapefile`로 받는다(기본값: `../data/raw/area/trdar.shp`).
- 상권 메타(이름, 구분, 자치구, 행정동): 서울 Open API `TbgisTrdarRelm` (DATA_NOTES A-0/A-1 참고, 1,650행 = 1,000건 페이지 2번). 키는 `ExternalProperties`의 서울 키.
- 서비스 업종: `docs/data/service_industries.csv` (100개)

## 만들 것
1. `V2__trade_areas.sql`
   - `service_industries (service_code varchar(10) PK, name, category varchar(10) check in ('FOOD','SERVICE','RETAIL'), has_sales boolean not null default false)` — category는 코드 앞자리 CS1/CS2/CS3
   - `trade_areas (area_code varchar(20) PK, name, area_type_code, area_type_name, district_code, district_name, dong_code, dong_name, area_m2 numeric, geom geometry(MultiPolygon,4326) not null, centroid geometry(Point,4326) not null, version_id bigint references data_versions(id))`
   - GiST 인덱스(geom, centroid), `district_code` 인덱스
   - 주석으로 좌표계 근거(DATA_NOTES A-3) 명시
2. SHP 읽기: Java로 구현한다. 라이브러리는 GeoTools(`gt-shapefile`, OSGeo 저장소 필요) 또는 더 가벼운 대안 중 고르고 이유를 보고. **좌표 변환은 DB에서** `ST_Transform(ST_SetSRID(ST_GeomFromWKB(?),5181),4326)` 방식으로 한다(앱에 좌표계 라이브러리를 넣지 않기 위해). 단일 폴리곤은 `ST_Multi`.
   - 먼저 PostGIS 이미지에 EPSG:5181이 있는지 `spatial_ref_sys`로 확인하고, 없으면 마이그레이션에서 추가(정의 근거 주석).
3. 수집기 2개 (`Collector` 구현, TASK-001 프레임워크 사용)
   - `seoul-area-shapes`: SHP 적재. 원본 저장은 SHP 전체가 아니라 파일 메타(파일명, 크기, SHA-256, 행 수)만 raw_payloads에 JSON으로.
   - `seoul-area-meta`: Open API로 메타 upsert. 원본 응답 페이지는 raw_payloads에 저장.
   - 둘 다 `INSERT ... ON CONFLICT (area_code) DO UPDATE`로 멱등. 같은 실행을 두 번 해도 행 수 그대로.
   - 적재가 끝나면 `data_versions`에 (source, period=null, READY→ACTIVE) 기록. 기존 ACTIVE는 READY로 내리고 새 것을 ACTIVE로 한 트랜잭션에서.
   - service_industries는 CSV로 시드하는 `seoul-service-industries` 수집기 또는 V2 안의 INSERT 중 택1 (이유 보고). has_sales는 DATA_NOTES 근거(매출 있는 62개)로 채운다.
4. 실행 경로: 관리자 화면은 아직 없으므로 `POST /admin/api/ingest/{source}?period=` 로 `IngestService.run`을 호출하는 임시 엔드포인트 (보안은 TASK-00x에서. 지금은 `app.admin.enabled=true`일 때만 등록되게 `@ConditionalOnProperty`).
5. 조회 API (`@RestController`)
   - `GET /api/areas?bbox=minLng,minLat,maxLng,maxLat&zoom=` → GeoJSON FeatureCollection. bbox와 겹치는 상권만, 줌이 낮으면 `ST_SimplifyPreserveTopology` 허용오차를 키운다. 속성: area_code, name, area_type_name, district_name. 최대 반환 개수 제한(예: 500)과 초과 시 표시.
   - `GET /api/areas/{code}` → 상권 1개 상세(속성 + 중심점)
   - `GET /api/areas/search?q=` → 이름 부분 일치 상위 20개
6. 테스트
   - 작은 테스트용 SHP(실제 SHP에서 3~5개 상권만 잘라 `src/test/resources`에 넣거나, 테스트에서 GeoTools로 생성) → 적재 후 행 수, SRID 4326, 좌표가 서울 범위(경도 126.7~127.3, 위도 37.4~37.7) 안
   - 메타 수집기: WireMock으로 `docs/data/samples`의 영역 샘플 응답 재생
   - 멱등성(두 번 실행), data_versions ACTIVE 전환
   - bbox API, 검색 API 웹 테스트

## 하지 않을 것
- 매출, 점포, 인구, 상가업소 적재(다른 작업), 프론트엔드, 보안, Resilience4j(002B가 공통 HTTP 클라이언트를 만듦. 여기서는 Spring `RestClient` 기본 사용, 타임아웃만 설정)

## 완료 조건 (직접 실행해서 보고)
1. `gradlew.bat clean check` 통과 (테스트 수)
2. 실제 데이터로 로컬 적재: bootRun → `POST /admin/api/ingest/seoul-area-shapes`, `seoul-area-meta` → DB에서 `select count(*), count(distinct area_type_name) from trade_areas` = 1,650 / 4, 모든 geom이 `ST_IsValid` (아니면 개수와 처리 방법 보고)
3. `curl "http://localhost:8081/api/areas?bbox=126.90,37.54,126.93,37.57&zoom=15"` 결과 요약(개수, 첫 상권 이름) — 망원·합정 부근
4. 같은 적재 2회 실행 후 행 수 동일
5. 키가 커밋에 없음(`git grep`)

## 보고 형식
선택한 라이브러리와 이유, EPSG:5181 확인 결과, 완료 조건 실제 결과, 남은 문제
