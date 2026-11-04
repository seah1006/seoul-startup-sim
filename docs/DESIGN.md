# 설계 문서 (초안 v0.1)

> 작성일: 2026-11-04. 기획 단계 정리본이며, 데이터를 실제로 적재해 본 뒤 계산식과 스키마를 다시 다듬습니다.

## 1. 개요

- **한 줄 소개**: 서울의 실제 상권 데이터로 돌아가는 창업 시뮬레이션 웹 게임
- **성격**: 대학 프레임워크 강의 기말과제 + 개인 포트폴리오 (개인 프로젝트)
- **설계 원칙**
  - 데이터를 빼면 게임이 성립하지 않아야 한다. (데이터가 장식이 아니라 게임판)
  - 게임 규칙과 판정은 서버가 한다. 클라이언트는 선택만 보낸다.
  - 게임 요청 중에는 외부 API를 호출하지 않는다. 외부 데이터는 배치로 수집해 DB에 저장하고, 게임은 DB만 읽는다.
  - 시드 + 플레이어 선택 + 데이터 버전이 같으면 결과가 같아야 한다.

## 2. 게임 루프 (1턴 = 1개월, 총 36개월)

1. **창업 준비**: 자본금을 받고 지도에서 서울의 실제 상권을 고른다. 상권 리포트에 유동인구, 직장인구, 점포 수, 개폐업률, 업종별 매출이 나온다.
2. **업종과 위치**: 업종을 고르고 가게 위치를 찍는다. 반경 안에 있는 실제 같은 업종 가게들이 경쟁자로 표시된다.
3. **운영**: 매달 가격 수준, 영업시간, 직원 수, 마케팅 비용을 정한다.
4. **월말 결산**: 서버가 매출, 비용, 순이익을 계산한다.
5. **분기 전환**: 게임 시간이 실제 분기 순서를 따라간다. 분기가 바뀌면 다음 분기의 실제 데이터가 반영된다.
6. **엔딩**: 36개월 생존 여부와 누적 순이익을 보여주고 공유 페이지를 만든다. 잔액이 2개월 연속 마이너스면 폐업 엔딩.

## 3. 계산식 v1 (초안)

### 시간
```
게임 월 m → 실제 분기 = start_quarter + floor(m / 3)
36개월 = 12분기 → 시작 분기는 2021년 1분기 ~ (최신 분기 - 11) 중에서 선택
```

### 매출
```
시장 규모(월) = 상권·업종 추정매출 (단위 확인 후 월 기준으로 환산)
기본 점유율   = 1 ÷ (상권 내 같은 업종 점포 수 + 1)
매력도        = f(가격, 영업시간, 직원, 마케팅)   평균 경쟁점 = 1.0, 프랜차이즈 = 1.3
밀집 보정     = g(반경 300m 같은 업종 수 ÷ 상권 평균 밀도)   많을수록 불리
매출          = 시장 규모 × 기본 점유율 × 매력도 × 밀집 보정 × 랜덤(시드)
                단, 직원 수 × 영업시간으로 정한 최대 처리량을 넘지 못함
```

### 비용
```
임대료 = 자치구별 고정값 (v1). 임대 시세 데이터를 API로 받을 수 있으면 교체
원가   = 매출 × 업종 분류별 원가율 (외식 / 서비스 / 소매 고정 테이블)
인건비 = 직원 수 × 월 인건비
마케팅 = 플레이어 입력
```

### 이벤트
```
경쟁점 폐업 = 상권 폐업률 기반 확률 → 내 점유율 상승
신규 경쟁점 = 상권 개업률 기반으로 가상 경쟁점 등장
위기 이벤트 = 폐업률이 높은 상권일수록 자주 발생 (공사, 임대료 인상 등)
```

계산식 상수(원가율, 매력도 가중치 등)는 DB 설정 테이블에 두고 관리자 화면에서 수정한다. 밸런스 조정 때 재배포가 필요 없게 한다.

## 4. 외부 데이터

| 출처 | 데이터 | 주기 | 용도 |
| --- | --- | --- | --- |
| 서울 열린데이터광장 (서울시 상권분석서비스) | 영역-상권 (공간 영역) | - | 지도, 상권 판정 |
| 〃 | 추정매출-상권 (상권별, 업종별 매출 금액/건수) | 분기 | 시장 규모 |
| 〃 | 점포-상권 (점포 수, 프랜차이즈 수, 개업률, 폐업률) | 분기/연간 | 경쟁 강도, 이벤트 확률 |
| 〃 | 길단위인구, 직장인구 | 분기 | 상권 리포트, 보정 |
| 공공데이터포털 (소상공인 상가(상권)정보 API) | 상가업소 (상호, 업종 소분류, 위경도) | 수시 | 실제 경쟁 점포 |
| 카카오 | 지도 JavaScript SDK | - | 지도 화면 |

- 서울시 상권분석서비스는 매 분기 종료 2개월 뒤에 업데이트되고, 현재 2021년 이후 자료만 제공한다.
- 이용 조건: 서울시 데이터는 공공누리 제1유형(출처표시), 상가정보는 이용허락범위 제한 없음. 화면에 출처를 표기한다.
- 상가정보 API의 `storeListInRadius`(반경내 상가업소)는 경쟁 점포 조회에, `storeListByDate`(수정일자 기준, 삭제 업소 포함)는 증분 수집에 쓴다.

### 적재 후 확인할 것 (미확정)
- [ ] 추정매출 "당월 매출 금액"의 단위가 월 기준인지 분기 합계인지
- [ ] 상권 영역 데이터의 좌표계 (국내 TM 계열이면 WGS84로 변환)
- [ ] 상권과 업종 조합의 매출 결측 비율과 결측 처리 정책
- [ ] 2024년 표준단위구역 변경 전후로 상권 코드가 바뀌었는지
- [ ] 임대 시세를 공개 API로 받을 수 있는지
- [ ] 소상공인 업종 소분류(247개)와 서울시 서비스업종 코드 매핑 방법

## 5. 서울 전체 범위에서 생기는 문제

| 문제 | 해결 |
| --- | --- |
| 두 출처의 업종 분류 체계가 다름 | `industry_mappings` 매핑 테이블 |
| 일부 상권·업종 조합에 매출 데이터 없음 | 결측 정책 (창업 불가 또는 자치구 평균 대체) 문서화 |
| 공간 데이터 좌표계와 지도 좌표계가 다를 수 있음 | 적재 시 `ST_Transform` |
| 서울 전체 상권 영역을 한 번에 그리면 무거움 | 화면 영역(bbox) 조회, `ST_Simplify`, 캐싱 |
| 분기마다 데이터 갱신 | 분기별 데이터 버전, 게임별 버전 고정 |

## 6. ERD 초안

### 참조 데이터 (수집, 분기별 버전)
```
data_versions      (id, source, period, status[loading|ready|active|failed], row_count, created_at, activated_at)
trade_areas        (area_code, name, area_type, district_code, dong_code,
                    geom MULTIPOLYGON(4326), centroid POINT, version_id)            GiST 인덱스
service_industries (service_code, name, category[외식|서비스|소매])
industry_mappings  (sbiz_small_code, service_code)
area_sales_q       (area_code, quarter, service_code, sales_amt, sales_cnt, version_id)
area_stores_q      (area_code, quarter, service_code, store_cnt, franchise_cnt, open_rate, close_rate, version_id)
area_population_q  (area_code, quarter, floating_pop, worker_pop, resident_pop, version_id)
stores             (sbiz_store_id, name, sbiz_small_code, service_code, geom POINT(4326), address, version_id)   GiST 인덱스
```

### 수집 기록
```
ingest_runs   (id, source, target_period, status, fetched, inserted, updated, failed, error_msg, started_at, finished_at)
raw_payloads  (id, run_id, source, request_key, payload JSONB, checksum, fetched_at)
```

### 게임
```
users             (id, kakao_id, nickname, role[USER|ADMIN])
worlds            (id, user_id, seed, start_quarter, current_month, status, version)
world_data_pins   (world_id, source, version_id)
shops             (id, world_id, area_code, service_code, name, geom, capital, opened_month, closed_month)
rivals            (id, world_id, sbiz_store_id NULL, is_virtual, service_code, geom, is_franchise, opened_month, closed_month)
monthly_decisions (world_id, month, price_level, open_hours, staff, marketing)     UNIQUE(world_id, month)
monthly_results   (world_id, month, market_size, share, revenue, cost, profit)     UNIQUE(world_id, month)
ledger_entries    (id, world_id, month, amount, type, memo)
game_events       (id, world_id, month, type, payload JSONB)
game_settings     (key, value, updated_at)                                         계산식 상수
hall_of_fame      (world_id, user_id, area_code, service_code, survived_months, total_profit)
```

- 게임 시작 시 반경 내 실제 점포를 `rivals`로 복사한다. 원본이 갱신돼도 진행 중인 게임은 바뀌지 않는다.
- `world_data_pins`로 게임마다 데이터 버전을 고정한다.
- `UNIQUE(world_id, month)`로 같은 달이 두 번 진행되는 것을 DB에서 막는다.

## 7. API 초안

### 게임 (React → `@RestController`, JSON)
```
POST /api/worlds                              새 게임 (시작 분기, 시드)
GET  /api/worlds/{id}                         현재 상태
GET  /api/areas?bbox=...&metric=...           지도 영역 내 상권 (단순화된 영역 + 지표)
GET  /api/areas/{code}/report?quarter=...     상권 리포트
GET  /api/areas/{code}/stores?service=...&radius=...   주변 경쟁 점포
POST /api/worlds/{id}/shop                    창업 (상권, 업종, 위치)
PUT  /api/worlds/{id}/decisions/{month}       이번 달 운영 결정
POST /api/worlds/{id}/advance                 다음 달 진행 (Idempotency-Key, version 필수)
GET  /api/worlds/{id}/results                 월별 결산
GET  /api/worlds/{id}/replay                  턴 기록으로 재현
GET  /api/hall-of-fame                        명예의 전당
```

### 관리자, 공유 (`@Controller`, Thymeleaf)
```
GET  /admin/ingest                 수집 대시보드 (출처별 마지막 성공 시각, 성공률, 건수, 서킷 브레이커 상태)
GET  /admin/ingest/runs/{id}       수집 실행 상세 (페이지별 결과, 실패 원인, 원본 보기)
POST /admin/ingest/run             지금 수집
GET  /admin/data-versions          데이터 버전 목록, 적용 버전 선택
GET  /admin/settings               계산식 상수 수정
GET  /share/{worldId}              엔딩 공유 페이지 (OG 태그)
GET  /about/data                   데이터 출처 페이지
```

## 8. 수집 파이프라인

```
@Scheduled 수집 스케줄러
   ↓
Collector 인터페이스 (상권 영역, 추정매출, 점포, 인구, 상가업소 수집기)
   ↓  RestClient + Resilience4j (RateLimiter, Retry + 지수 백오프, CircuitBreaker)
외부 응답 DTO (XML/JSON)
   ↓
raw_payloads 원본 저장 → 정규화 → upsert (대량은 JDBC batch / COPY)
   ↓
ingest_runs 수집 이력 → data_versions 갱신
```

- 초기 적재는 CSV/SHP 파일, 이후 갱신은 API로 한다.
- 중복 저장 방지: `(source, external_id)` 유니크 키로 upsert.
- 테스트: 실제 응답을 녹화해 WireMock으로 재생 (외부 서버 없이 CI 동작).
- 장애 시: 수집이 실패해도 게임은 마지막 활성 데이터 버전으로 동작.

## 9. 화면

| 화면 | 기술 |
| --- | --- |
| 지도에서 상권 고르기, 상권 리포트, 가게 운영, 월말 결산 | React + 카카오맵 |
| 관리자 수집 대시보드, 데이터 버전, 설정 | Thymeleaf |
| 엔딩 공유 페이지, 데이터 출처 페이지 | Thymeleaf |

## 10. 패키지 구조 (예정)

```
ingest/     수집 (collector, client, dto, domain: RawPayload, IngestRun, DataVersion)
catalog/    정규화된 참조 데이터 (TradeArea, AreaSales, AreaStores, Store ...)
game/       게임 엔진 (World, Shop, Rival, MonthlyResult, Ledger ...)
auth/       회원, 카카오 OAuth2
api/        @RestController (React용)
web/        @Controller (Thymeleaf)
common/     공통 예외 처리, 응답 형식
```

## 11. 결정 기록

| 결정 | 이유 |
| --- | --- |
| 소재: 동네 창업 시뮬레이션 | 기획사, 버튜버 등 다른 소재는 외부 데이터가 게임에 억지로 붙는 느낌. 상권 데이터는 그 자체가 게임판이 됨 |
| 범위: 서울 전체 | 대량 적재와 데이터 품질 문제를 정면으로 다루기 위해 |
| 싱글 플레이 (턴제) | 동시 접속자가 적어도 게임이 성립해야 함 |
| Spring Boot + Thymeleaf + React | 프레임워크 강의(Spring MVC) 범위 반영. 게임은 React, 관리자와 공유 페이지는 서버 렌더링 |
| Thymeleaf (JSP 대신) | Spring Boot의 jar 실행 방식에서 JSP는 war 패키징 등 제약이 있음 |
| PostgreSQL + PostGIS | 공간 인덱스, JSONB 원본 저장, `ON CONFLICT` upsert, advisory lock |

## 12. 로드맵 (6주 예시, 마감일 확정 후 조정)

| 주차 | 할 일 |
| --- | --- |
| 1주 | 프로젝트 세팅, PostGIS, Flyway, 상권 영역 적재, 수집 프레임워크 뼈대, 추정매출 1개 분기 적재 |
| 2주 | 속도 제한, 재시도, 증분 수집, 수집 이력, WireMock 테스트, 상가업소 대량 적재 |
| 3주 | 나머지 수집기, Thymeleaf 관리자 대시보드, 데이터 버전 |
| 4주 | 게임 엔진: 월드, 턴 진행, 매출 계산, 원장 |
| 5주 | React 화면 (지도, 상권 리포트, 운영, 결산) |
| 6주 | 카카오 로그인, 배포, 시연 시나리오, 발표 자료 |
