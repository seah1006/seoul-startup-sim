# TASK-003D: 카탈로그 조회 API + 업종 매핑 + 직장인구 수집

- 브랜치: `task/TASK-003D` (worktree `C:\Users\my\dev\sss-003d`, DB 5436, 앱 8084)
- 담당: Codex
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 9~11장, `docs/data/DATA_NOTES.md`, **`docs/api/API_CONTRACT.md` 1장(카탈로그)**
- **Flyway: `V7__catalog.sql` 만 사용**

## 만들 것
1. **업종 매핑**
   - `industry_mappings (sbiz_small_code PK, service_code, match_type check in ('EXACT','MANUAL','NONE'), note)` — `docs/data/industry_mapping_draft.csv`를 시드(exact + manual 중 확신 가능한 것. 외식 업종 10개에 해당하는 소분류는 사람이 보기에 맞는지 직접 점검해서 표로 보고)
   - `stores`에 `service_code` 컬럼 추가 + 인덱스 `(service_code)`, GiST는 이미 있음. 적재 후 매핑으로 채우는 SQL(수집기 후처리)
   - `IndustryMappingPort` 실제 구현 (003A가 임시 구현과 인터페이스를 만든다. 이름/시그니처: `Optional<String> toServiceCode(String sbizSmallCode)`, `List<String> sbizCodesOf(String serviceCode)`. 같은 이름의 인터페이스를 `catalog` 패키지에 만들고, 병합 때 메인이 하나로 합친다)
2. **카탈로그 API** — 계약 1장 그대로
   - `GET /api/industries?playable=true`
   - `GET /api/areas/{areaCode}/report?quarter=` (매출 null이면 자치구 점포당 매출 × 점포 수로 추정, `salesEstimated=true`). 자치구 추정에 필요한 집계는 SQL로
   - `GET /api/areas/{areaCode}/stores?serviceCode=&lng=&lat=&radius=` (반경은 50~1000m 검증, GiST 사용하는 쿼리 — geom bbox 후보 + geography 정확 거리)
   - 기존 `/api/areas`, `/search`, `/{code}` 응답 필드명이 계약과 다르면 계약에 맞춘다
3. **직장인구 수집기** `seoul-area-workers` (VwsmTrdarWrcPopltnQq, DATA_NOTES 권고: 분기 인자 없음 → 전체 받아 분기별로 나눠 저장) + `area_worker_pop_q` 테이블. 002B의 `SeoulOpenApiClient`와 수집 프레임워크 재사용
4. 테이블 FK 정리: `area_sales_q`, `area_stores_q`, `area_floating_pop_q`의 `area_code` → `trade_areas`, `service_code` → `service_industries` FK 추가 (기존 데이터가 어긋나면 개수 보고, FK는 `NOT VALID`로 추가 후 VALIDATE 시도)

## 테스트
- 리포트: 매출 있는 업종, 매출 없는 업종(추정), 분기 생략 시 최신
- 반경 조회: 경계(299m/301m), 업종 필터, 최대 100건, 잘못된 반경 400
- 직장인구 수집: WireMock(실제 샘플 기반), 숫자 `.0` 표기 포함
- 매핑: 외식 10개 각각 하나 이상의 소분류가 매핑됨

## 완료 조건
1. `gradlew.bat clean check --offline` 통과
2. 실제 데이터 검증은 메인 DB(5433)에서 메인 에이전트가 한다. 검증용 SQL/curl 스크립트를 `docs/tasks/TASK-003D-verify.ps1`로 남긴다
