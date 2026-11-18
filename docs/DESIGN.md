# 설계 문서 (초안 v0.2)

> 기획 단계 정리본입니다. 데이터를 실제로 적재해 본 뒤 계산식과 스키마를 다시 다듬습니다.

## 0. 변경 이력

| 버전 | 날짜 | 내용 |
| --- | --- | --- |
| v0.1 | 2026-11-04 | 첫 초안 (월 단위 턴, 계산식, ERD, API, 수집 파이프라인) |
| v0.2 | 2026-11-06 | 커리어 시뮬레이션 구조 반영: 턴을 분기 단위로 변경, 사장님 생성, 분기 결산 카드, 전환점 이벤트, 동네 반응, 엔딩과 랭킹, 게스트 플레이 추가 |
| v0.3 | 2026-11-07 | DATA-001 조사 결과 반영: 매출은 분기 합계, 좌표계 EPSG:5181, 결측 정책, 상가업소 수집 방식(증분 불가 → 분기 스냅샷), 임대료는 고정값 확정 (근거: `docs/data/DATA_NOTES.md`) |
| v0.4 | 2026-11-18 | 19장 사장님 커뮤니티(게시판) 추가: RECORD 엔딩 인증글, 댓글·좋아요·신고, 관리자 숨김. V11 |

## 1. 개요

- **한 줄 소개**: 서울의 실제 상권 데이터로 돌아가는 창업 커리어 시뮬레이션 웹 게임
- **성격**: 대학 프레임워크 강의 기말과제 + 개인 포트폴리오 (개인 프로젝트)
- **한 판의 길이**: 분기 버튼을 눌러 가며 20분 안팎에 엔딩까지 가는 가벼운 커리어 시뮬레이션
- **설계 원칙**
  - 데이터를 빼면 게임이 성립하지 않아야 한다. (데이터가 장식이 아니라 게임판)
  - 게임 규칙과 판정은 서버가 한다. 클라이언트는 선택만 보낸다. 그래서 랭킹 기록을 조작할 수 없다.
  - 게임 요청 중에는 외부 API를 호출하지 않는다. 외부 데이터는 배치로 수집해 DB에 저장하고, 게임은 DB만 읽는다.
  - 시드 + 플레이어 선택 + 데이터 버전이 같으면 결과가 같아야 한다.
- **참고한 구조**: 웹 커리어 시뮬레이션 게임들의 "같은 출발선에서 분포만 다른 캐릭터 생성 → 시즌 결과 카드 → 전환점 선택 → 은퇴 후 랭킹" 흐름. 구조만 참고하고 문구와 디자인은 직접 만든다.

## 2. 게임 루프 (1턴 = 1분기)

```
사장님 만들기 → 상권과 업종 고르기 → [분기 운영 결정 → 분기 결산 카드 → (전환점)] 반복 → 엔딩 → 랭킹 등록
```

1. **사장님 만들기**: 이름, 배경을 고르고 능력치 분포가 다른 후보 중 하나를 선택한다. 집중해서 키울 능력치를 최대 2개 고른다.
2. **상권과 업종 고르기**: 지도에서 서울의 실제 상권을 검색하거나 골라서 업종과 가게 위치를 정한다. 상권 리포트(유동인구, 직장인구, 점포 수, 개폐업률, 업종별 매출)와 반경 내 실제 같은 업종 점포가 표시된다.
3. **분기 운영 결정**: 가격 수준, 영업시간, 직원 수, 마케팅 비용, 사장님 본인의 이번 분기 집중 활동(메뉴 개발, 단골 관리, 경영 공부, 휴식 등)을 정한다.
4. **분기 결산 카드**: 서버가 계산한 결과를 카드 한 장으로 보여준다. (4장 참고)
5. **전환점**: 몇 분기에 한 번 선택지가 나온다. (5장 참고)
6. **분기 전환**: 게임 시간이 실제 분기 순서를 따라간다. 다음 분기의 실제 데이터가 반영된다.
7. **엔딩**: 폐업, 매각, 장수 가게 등. 엔딩이 나면 자동으로 랭킹에 등록되고 공유 페이지가 만들어진다.

### 왜 분기 단위인가
- 서울시 상권분석 데이터가 원래 분기 단위라서 데이터 갱신과 턴이 정확히 맞는다.
- 월 단위면 3년에 36번, 분기 단위면 12번이라 한 판이 짧고 결과 카드 한 장 한 장의 무게가 커진다.
- 데이터가 2021년 이후만 있으므로 한 판은 최대 약 20분기다.

## 3. 사장님 만들기

### 능력치 (6종)
| 능력치 | 영향 |
| --- | --- |
| 요리(상품력) | 상품 품질, 재방문율 |
| 서비스 | 리뷰 점수, 단골 전환 |
| 경영 | 원가율, 인건비 효율, 위기 대응 |
| 마케팅 | 신규 방문객, 마케팅 비용 효율 |
| 체력 | 영업시간 확대 한도, 번아웃 위험 |
| 인맥 | 전환점 성공 확률, 좋은 제안 빈도 |

### 규칙
- 모든 후보의 능력치 합계는 같다. (예: 합계 240) 배경에 따라 분포만 다르다.
- 배경 예시: 전직 직장인(경영, 마케팅 높음), 요리학교 출신(요리 높음), 알바 10년차(서비스, 체력 높음), 동네 토박이(인맥 높음)
- 집중 육성 능력치 최대 2개. 고른 능력치가 더 빨리 자란다. 전환점에서 바꿀 수 있다.
- 성장 요인: 분기 집중 활동, 영업 경험(영업 분기 수), 전환점 결과. 체력이 낮은데 무리하면 번아웃 이벤트.

## 4. 분기 결산 카드

결과를 숫자 하나로 먼저 보여주고, 아래로 내려가며 자세한 내용을 보여준다.

```
2023년 2분기가 끝났어요
이번 분기 순이익  1,240만 원

매출 · 방문객 · 객단가        5,820만 원 · 4,130명 · 14,100원
상권 내 같은 업종 순위         12곳 중 4위
능력치 변화                   요리 52 (+3) · 서비스 47 (+2)

상권 소식     이 상권 카페 업종 개업 2곳, 폐업 1곳 (실제 데이터 기준)
이번 분기의 순간   단골손님이 친구들을 데려왔어요. (서비스 판정 성공)
동네 반응     가상 리뷰 · 동네 커뮤니티 글 2~3개
```

- 상권 소식의 숫자는 실제 데이터(점포-상권의 개업, 폐업 수)에서 가져온다.
- 화면 아래에 "동네 반응은 게임 속 가상 반응입니다"를 표기한다.

## 5. 전환점 이벤트

몇 분기에 한 번 나오는 선택지. 고르기 전에 선택지마다 성공 확률과 성공 시, 실패 시 결과를 보여준다.

### 예시
| 전환점 | 발생 조건 | 선택지 예시 |
| --- | --- | --- |
| 프랜차이즈 가맹 제안 | 상권 내 순위 상위, 2년 이상 영업 | 가맹 / 거절하고 독립 유지 |
| 2호점 확장 | 누적 순이익 일정 이상 | 같은 상권 / 다른 상권 / 보류 |
| 임대료 인상 통보 | 상권 매출 상승 추세 | 수용 / 협상(인맥 판정) / 이전 |
| 배달앱 입점 | 외식 업종 | 입점 / 홀 영업 집중 |
| 맛집 유튜버 섭외 | 요리 또는 서비스 능력치 일정 이상 | 수락 / 거절 |
| 재개발 소문 | 실제 데이터에서 상권 폐업률 급증 | 버티기 / 매각 / 이전 |
| 직원 이탈 | 직원 수 2명 이상, 경영 낮음 | 연봉 인상 / 새로 채용 / 혼자 운영 |
| 번아웃 | 체력 낮은데 영업시간 최대 | 휴업 한 분기 / 강행 |

- 전환점 정의(조건, 선택지, 확률 공식, 효과)는 DB 테이블에 두고 관리자 화면에서 추가, 수정한다.
- 발생 판정과 성공 판정은 서버가 시드 기반 랜덤으로 한다.
- 기말 범위: 전환점 10~15개.

## 6. 동네 반응

- 결과에 맞는 템플릿 문장을 골라 가상 리뷰, 동네 커뮤니티 글을 만든다. (LLM 없이 템플릿으로 시작)
- **실제 상호 사용 규칙**: 실제 가게 상호는 지도와 상권 리포트에 사실 정보로만 표시한다. 가상 이벤트, 가상 반응, 경쟁점 폐업 같은 연출에는 실제 상호를 쓰지 않는다.
- 실제 매체나 플랫폼 이름을 흉내 내지 않고 가상 이름을 쓴다.

## 7. 엔딩과 랭킹

### 엔딩
| 엔딩 | 조건 |
| --- | --- |
| 폐업 | 잔액이 2분기 연속 마이너스 |
| 매각 | 플레이어가 매각을 선택 (가게 가치로 정산) |
| 장수 가게 | 데이터의 마지막 분기까지 생존 |
| 프랜차이즈 본사 | 매장 수 일정 이상 + 가맹 전환점 성공 |

### 랭킹
- 엔딩이 나면 자동 등록. 한국 시간 기준 그날 등록된 기록을 집계한다.
- 30분마다 배치로 갱신하고 결과를 캐시한다. 최근 7일 조회.
- 순위 비교 순서: 생존 분기 수 → 누적 순이익 → 최고 분기 순이익 → 매장 수. 모두 같으면 공동 순위.
- 종류: 전체, 업종별 1위, 상권별 1위 ("망원동에서 가장 성공한 카페")
- 서버가 모든 결과를 계산하므로 클라이언트 조작으로 기록을 만들 수 없다. 별도 검수 없이 랭킹을 신뢰할 수 있다.

## 8. 계산식 v1 (분기 기준, 초안)

### 시간
```
게임 분기 q → 실제 분기 = start_quarter + q
시작 분기는 2021년 1분기 ~ (최신 분기 - 최소 플레이 분기) 중에서 선택
```

### 매출
```
시장 규모(분기) = 상권·업종 추정매출 THSMON_SELNG_AMT (분기 3개월 합계로 확인됨, 환산 없이 사용)
                  매출 자료가 없으면: 같은 자치구·업종의 점포당 매출 × 해당 상권 점포 수 (화면에 "추정치" 표시)
기본 점유율     = 1 ÷ (상권 내 같은 업종 점포 수 + 1)
매력도          = f(가격, 영업시간, 직원, 마케팅, 사장님 능력치)   평균 경쟁점 = 1.0, 프랜차이즈 = 1.3
밀집 보정       = g(반경 300m 같은 업종 수 ÷ 상권 평균 밀도)       많을수록 불리
매출            = 시장 규모 × 기본 점유율 × 매력도 × 밀집 보정 × 랜덤(시드)
                  단, 직원 수 × 영업시간으로 정한 최대 처리량을 넘지 못함
```

### 비용
```
임대료 = 자치구별 고정값 (서울 열린데이터광장에 임대시세 API/파일이 없음을 확인. 관리자 설정값으로 관리)
원가   = 매출 × 업종 분류별 원가율 × 경영 능력치 보정
인건비 = 직원 수 × 분기 인건비
마케팅 = 플레이어 입력 × 마케팅 능력치 효율
```

### 이벤트 확률
```
경쟁점 폐업 = 상권 폐업률 기반 → 내 점유율 상승
신규 경쟁점 = 상권 개업률 기반으로 가상 경쟁점 등장
전환점 발생 = 조건 충족 시 기본 확률 + 인맥 보정
```

계산식 상수(원가율, 매력도 가중치, 전환점 확률 등)는 DB 설정 테이블에 두고 관리자 화면에서 수정한다. 밸런스 조정 때 재배포가 필요 없다.

## 9. 외부 데이터

### 분기 참조 버전 보존 (TASK-004E)
- 매출·점포·유동인구·직장인구 분기 테이블은 `(version_id, quarter, area_code[, service_code])` 기본키로 과거 행을 보존한다. 재수집은 새 버전에 INSERT하고 성공한 경우에만 ACTIVE를 전환한다.
- 카탈로그는 `*_active` 뷰를, 게임은 `game_data_pins`의 고정 버전을 읽는다. 게임 생성은 REPEATABLE_READ로 분기 목록과 전체 ACTIVE 버전을 같은 스냅샷에서 고정한다.
- `stores`, `trade_areas`는 창업 시 `rivals`, `shops`에 필요한 값을 복사하므로 분기 버전 보존 대상에서 제외한다.


| 출처 | 데이터 | 주기 | 용도 |
| --- | --- | --- | --- |
| 서울 열린데이터광장 (서울시 상권분석서비스) | 영역-상권 (공간 영역) | - | 지도, 상권 판정 |
| 〃 | 추정매출-상권 (상권별, 업종별 매출 금액/건수) | 분기 | 시장 규모 |
| 〃 | 점포-상권 (점포 수, 프랜차이즈 수, 개업률, 폐업률) | 분기/연간 | 경쟁 강도, 상권 소식, 이벤트 확률 |
| 〃 | 길단위인구, 직장인구 | 분기 | 상권 리포트, 보정 |
| 공공데이터포털 (소상공인 상가(상권)정보 API) | 상가업소 (상호, 업종 소분류, 위경도) | 수시 | 실제 경쟁 점포 |
| 카카오 | 지도 JavaScript SDK | - | 지도 화면 |

- 서울시 상권분석서비스는 매 분기 종료 2개월 뒤에 업데이트되고, 현재 2021년 이후 자료만 제공한다.
- 이용 조건: 서울시 데이터는 공공누리 제1유형(출처표시), 상가정보는 이용허락범위 제한 없음. 화면에 출처를 표기한다.
- 상가정보 API의 `storeListInRadius`(반경내 상가업소)는 경쟁 점포 조회에 쓴다. `storeListByDate`는 배포일 하루에만 데이터가 있고 삭제 정보가 없어 **증분 수집에 쓸 수 없다**(DATA-001에서 확인). 대신 분기마다 서울 전체를 다시 받아 이전 스냅샷과 비교한다(새로 생긴 업소, 사라진 업소 = 폐업).

### DATA-001에서 확인된 것 (자세한 근거: `docs/data/DATA_NOTES.md`)
- [x] 추정매출 `THSMON_SELNG_AMT`는 **분기 3개월 합계**. 최댓값 약 7,670억이라 BIGINT 필요
- [x] 상권 영역 좌표계는 **EPSG:5181**. 폴리곤은 SHP 파일로만 제공(API는 중심점과 면적만). 적재 시 4326으로 변환, MULTIPOLYGON
- [x] 상권 1,650개 (골목 1,090 / 발달 249 / 전통시장 305 / 관광특구 6), 서비스 업종 100개 중 매출 자료가 있는 업종 62개
- [x] 제공 범위 2021년 1분기 ~ 2026년 2분기. 현재 제공 데이터는 전 기간 같은 상권 코드(변경 없음)
- [x] 매출은 같은 업종 점포 3개 이상인 조합에만 있음. 점포 3개 이상 외식 업종은 85.2%가 매출 보유. 매출이 전혀 없는 상권 84개
- [x] 임대 시세는 API, 파일 모두 없음 → 자치구별 고정값
- [x] 업종 매핑: 소분류 247개 중 exact 50 / manual 109 / none 88 (초안 `docs/data/industry_mapping_draft.csv`)
- [x] 상가업소: 서울 552,422건, API 페이지당 최대 1,000건(JSON). 분기별 CSV 파일도 제공

### 결측 정책 (v1 확정)
- 창업 가능 업종: 매출 자료가 있는 62개 중 **v1은 외식 10개**로 시작
- 상권·업종 매출이 없으면 자치구 점포당 매출로 대체하고 "추정치"로 표시
- 매출이 하나도 없는 상권 84개는 창업 상권 목록에서 제외
- 업종 분류(외식/서비스/소매)는 서비스 업종 코드 앞자리(CS1/CS2/CS3)로 구분
- 소매 업종은 두 출처의 점포 수 차이가 커서, 경쟁 강도는 서울시 점포 수를 기준으로 하고 상가업소는 지도 표시에만 쓴다

### 아직 미확인 (TASK-002 이후 확인)
- [ ] 길단위인구 `TOT_FLPOP_CO`의 단위 (분기 합계 / 일평균)
- [ ] PostGIS 이미지에 EPSG:5181 정의가 있는지
- [ ] 업종 매핑 manual 109건 확정
- [ ] 서울 열린데이터광장 API 일일 호출 한도

## 10. 서울 전체 범위에서 생기는 문제

| 문제 | 해결 |
| --- | --- |
| 두 출처의 업종 분류 체계가 다름 | `industry_mappings` 매핑 테이블 |
| 일부 상권·업종 조합에 매출 데이터 없음 | 결측 정책 (창업 불가 또는 자치구 평균 대체) 문서화 |
| 공간 데이터 좌표계와 지도 좌표계가 다를 수 있음 | 적재 시 `ST_Transform` |
| 서울 전체 상권 영역을 한 번에 그리면 무거움 | 화면 영역(bbox) 조회, `ST_Simplify`, 캐싱 |
| 분기마다 데이터 갱신 | 분기별 데이터 버전, 게임별 버전 고정 |

## 11. ERD 초안

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

### 회원
```
users  (id, kakao_id NULL, nickname, is_guest, guest_token_hash NULL, role[USER|ADMIN], created_at)
```
- 게스트로 바로 시작하고, 카카오 로그인 시 같은 `users` 행을 정회원으로 전환한다. 기록은 그대로 유지된다.

### 게임
```
games              (id, user_id, seed, start_quarter, current_turn, status[playing|ended], ending_type, version)
game_data_pins     (game_id, source, version_id)
owners             (game_id, name, background, cooking, service, management, marketing, stamina, network,
                    focus_stat_1, focus_stat_2)
shops              (id, game_id, area_code, service_code, name, geom, capital, opened_turn, closed_turn)
rivals             (id, game_id, sbiz_store_id NULL, is_virtual, service_code, geom, is_franchise, opened_turn, closed_turn)
turn_decisions     (game_id, turn, price_level, open_hours, staff, marketing, owner_activity)   UNIQUE(game_id, turn)
turn_results       (game_id, turn, market_size, share, revenue, visitors, cost, profit, area_rank,
                    stat_changes JSONB, reactions JSONB)                                        UNIQUE(game_id, turn)
ledger_entries     (id, game_id, turn, amount, type, memo)
```

### 전환점, 반응
```
turning_points         (id, code, title, condition_expr JSONB, base_probability, enabled)
turning_point_options  (id, turning_point_id, label, success_formula JSONB, on_success JSONB, on_fail JSONB)
game_turning_points    (game_id, turn, turning_point_id, chosen_option_id NULL, success NULL, resolved_at)
reaction_templates     (id, kind[review|community], condition_expr JSONB, text, enabled)
```

### 랭킹, 운영
```
game_records       (game_id, user_id, nickname, ending_type, survived_quarters, total_profit, best_quarter_profit,
                    shop_count, area_code, service_code, ended_at)        엔딩 시 1회 기록
ranking_snapshots  (ranking_date, kind[all|industry|area], key, rank, game_id, refreshed_at)   30분 배치 결과
game_settings      (key, value, updated_at)                               계산식 상수
notices            (id, title, body, published_at)                         공지, 패치노트
```

- 게임 시작 시 반경 내 실제 점포를 `rivals`로 복사한다. 원본이 갱신돼도 진행 중인 게임은 바뀌지 않는다.
- `game_data_pins`로 게임마다 데이터 버전을 고정한다.
- `UNIQUE(game_id, turn)`로 같은 분기가 두 번 진행되는 것을 DB에서 막는다.

## 12. API 초안

### 게임 (React → `@RestController`, JSON)
```
POST /api/guest                                   게스트 시작 (게스트 토큰 발급)
GET  /api/owners/candidates?background=...        사장님 후보 (합계 같고 분포만 다른 후보들)
POST /api/games                                   새 게임 (사장님, 시작 분기)
GET  /api/games/{id}                              현재 상태
GET  /api/areas?bbox=...&metric=...               지도 영역 내 상권 (단순화된 영역 + 지표)
GET  /api/areas/search?q=...                      상권 이름 검색
GET  /api/areas/{code}/report?quarter=...         상권 리포트
GET  /api/areas/{code}/stores?service=...&radius=...   주변 실제 경쟁 점포
POST /api/games/{id}/shop                         창업 (상권, 업종, 위치)
PUT  /api/games/{id}/turns/{turn}/decision        이번 분기 운영 결정
POST /api/games/{id}/advance                      다음 분기 진행 (Idempotency-Key, version 필수) → 결산 카드 반환
GET  /api/games/{id}/turning-point                대기 중인 전환점 (선택지별 성공 확률 포함)
POST /api/games/{id}/turning-point/choice         전환점 선택 → 판정 결과
GET  /api/games/{id}/turns                        분기별 기록
GET  /api/games/{id}/replay                       턴 기록으로 재현
GET  /api/rankings?date=...&kind=...&key=...      오늘의 사장님 랭킹
GET  /api/notices                                 공지, 패치노트
```

### 관리자, 공유 (`@Controller`, Thymeleaf)
```
GET  /admin/ingest                 수집 대시보드 (출처별 마지막 성공 시각, 성공률, 건수, 서킷 브레이커 상태)
GET  /admin/ingest/runs/{id}       수집 실행 상세 (페이지별 결과, 실패 원인, 원본 보기)
POST /admin/ingest/run             지금 수집
GET  /admin/data-versions          데이터 버전 목록, 적용 버전 선택
GET  /admin/turning-points         전환점 정의 관리
GET  /admin/reactions              반응 템플릿 관리
GET  /admin/settings               계산식 상수 수정
GET  /admin/notices                공지, 패치노트 작성
GET  /share/{gameId}               엔딩 공유 페이지 (OG 태그)
GET  /about/data                   데이터 출처 페이지
```

## 13. 수집 파이프라인

### 분기 수집 트랜잭션 경계 (TASK-004E)
- 외부 API 페이지 수집과 원본 저장은 적재 트랜잭션 밖에서 먼저 완료한다. 원본은 기존 페이지별 독립 트랜잭션으로 보존한다.
- 최대 약 8만 행을 메모리에 모은 뒤 짧은 DB 트랜잭션에서 새 버전 INSERT와 활성화를 원자적으로 처리한다. 원본 재파싱을 피하고 중간 페이지 실패 시 적재 자체를 시작하지 않기 위한 선택이다. 직장인구도 수집 완료 후 적재한다.


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

## 14. 화면

| 화면 | 기술 |
| --- | --- |
| 홈 (시작, 오늘의 랭킹 미리보기, 공지) | React |
| 사장님 만들기, 상권 고르기(지도), 상권 리포트 | React + 카카오맵 |
| 분기 운영, 분기 결산 카드, 전환점, 기록 | React |
| 랭킹 전체 보기 | React |
| 관리자 (수집, 데이터 버전, 전환점, 반응, 설정, 공지) | Thymeleaf |
| 엔딩 공유 페이지, 데이터 출처 페이지 | Thymeleaf |

## 15. 패키지 구조 (예정)

```
ingest/     수집 (collector, client, dto, domain: RawPayload, IngestRun, DataVersion)
catalog/    정규화된 참조 데이터 (TradeArea, AreaSales, AreaStores, Store ...)
game/       게임 엔진 (Game, Owner, Shop, Rival, TurnResult, Ledger ...)
event/      전환점, 동네 반응 (TurningPoint, ReactionTemplate, 조건 평가기)
ranking/    기록, 랭킹 배치
auth/       게스트, 카카오 OAuth2
api/        @RestController (React용)
web/        @Controller (Thymeleaf)
common/     공통 예외 처리, 응답 형식
```

## 16. 결정 기록

### 2026-11-18: 분기 참조 데이터 append-only 보존
- 진행 중 게임의 재현성을 위해 분기 참조 데이터만 버전별 불변으로 보존한다.
- 상가업소와 상권 메타는 창업 시 복사되는 스냅샷이므로 과거 전체 데이터 보존 대상에서 제외한다. 창업은 현재 테이블의 최신 상권·점포를 읽고 고정 버전 검사에서 제외한다.
- 고정 버전의 삭제는 `game_data_pins.version_id` FK의 `ON DELETE RESTRICT`로 차단한다.


| 결정 | 이유 |
| --- | --- |
| 소재: 동네 창업 시뮬레이션 | 기획사, 버튜버 등 다른 소재는 외부 데이터가 게임에 억지로 붙는 느낌. 상권 데이터는 그 자체가 게임판이 됨 |
| 범위: 서울 전체 | 대량 적재와 데이터 품질 문제를 정면으로 다루기 위해 |
| 싱글 플레이 (턴제) + 랭킹 | 동시 접속자가 적어도 게임이 성립해야 함. 다른 사람과의 비교는 랭킹으로 |
| 턴 = 분기 | 데이터 갱신 주기와 일치, 한 판을 20분 안팎으로 |
| 커리어 시뮬레이션 구조 (결과 카드, 전환점, 랭킹) | 숫자 계산만으로는 재미가 약함. 검증된 웹게임 흐름을 참고 |
| 서버 판정 | 랭킹 기록 조작 방지, 검수 불필요 |
| 게스트 플레이 → 카카오 로그인 전환 | 가입 없이 바로 시작하는 진입 장벽 낮추기 |
| 실제 상호는 사실 정보에만 | 실제 소상공인 가게에 가상의 부정적 연출이 붙지 않도록 |
| Spring Boot + Thymeleaf + React | 프레임워크 강의(Spring MVC) 범위 반영. 게임은 React, 관리자와 공유 페이지는 서버 렌더링 |
| Thymeleaf (JSP 대신) | Spring Boot의 jar 실행 방식에서 JSP는 war 패키징 등 제약이 있음 |
| PostgreSQL + PostGIS | 공간 인덱스, JSONB 원본 저장, `ON CONFLICT` upsert, advisory lock |
| 커뮤니티 게시판 추가 (19장) | 싱글 게임에서도 엔딩 기록 공유와 공략으로 사용자끼리 연결. 기말 평가의 게시판 CRUD를 정면으로 보여줌 |
| 게시판 사용자 화면 React, 관리자 Thymeleaf | 게스트 토큰 헤더 인증과 SPA 흐름 유지, Spring MVC 폼·PRG는 관리자에서 시연 |
| RECORD 스냅샷 + DB 트리거 이중 방어 | 게임 데이터가 바뀌어도 글은 그대로, 직접 SQL로도 남의 게임·진행 중 게임 인증 불가 |

## 17. 범위

| 기말 제출 (MVP) | 이후 (개인 프로젝트로 확장) |
| --- | --- |
| 수집 파이프라인, 관리자 대시보드 | 전환점 추가 확장, LLM 기반 반응 |
| 사장님 생성 (배경 4종) | 2호점 이상 다점포 운영 고도화 |
| 분기 턴, 결산 카드, 전환점 10~15개 | 상권별 랭킹, 7일 랭킹 이력 |
| 동네 반응 템플릿 | 부하 테스트, 모니터링 |
| 엔딩, 전체·업종별 랭킹 (30분 배치) | 데이터 버전 리플레이 화면 |
| 게스트 플레이, 카카오 로그인, 배포 | |

## 18. 로드맵 (6주 예시, 마감일 확정 후 조정)

| 주차 | 할 일 |
| --- | --- |
| 1주 | 프로젝트 세팅, PostGIS, Flyway, 상권 영역 적재, 수집 프레임워크 뼈대, 추정매출 1개 분기 적재 |
| 2주 | 속도 제한, 재시도, 증분 수집, 수집 이력, WireMock 테스트, 상가업소 대량 적재 |
| 3주 | 나머지 수집기, Thymeleaf 관리자 대시보드, 데이터 버전 |
| 4주 | 게임 엔진: 사장님, 분기 턴, 매출 계산, 원장, 전환점 엔진 |
| 5주 | React 화면 (홈, 사장님 만들기, 지도, 결산 카드, 전환점), 동네 반응, 엔딩 |
| 6주 | 랭킹 배치, 게스트와 카카오 로그인, 배포, 시연 시나리오, 발표 자료 |

## 19. 사장님 커뮤니티 (게시판) — v0.4, 2026-11-18

> 상세: API `docs/api/API_CONTRACT.md` 4장, DB `docs/board/V11__board.sql`, 근거와 대안 `docs/board/drafts/`(DB/API/기획 초안). 지시서 TASK-005A/B/C.

### 왜 넣는가
- 싱글 플레이 게임이라도 "다른 사장님과의 비교"는 랭킹만으로는 약하다. 엔딩 결과를 글로 남기고 공략을 나누는 커뮤니티가 있으면 한 판이 끝난 뒤에도 서비스에 머물 이유가 생긴다.
- 기말과제 평가에서 게시판 CRUD(페이징, 검색, 정렬, 검증, 권한, 낙관적 락, 댓글, 좋아요, 신고)를 정면으로 보여줄 수 있다.
- 게시판이 게임과 따로 놀지 않도록 **엔딩 인증글(RECORD)** 로 게임 데이터와 연결한다.

### 구성
| 카테고리 | 내용 |
| --- | --- |
| FREE 자유 / TIP 공략 / QNA 질문 | 일반 게시판 |
| RECORD 엔딩 인증 | 끝난 내 게임(ENDED)을 첨부. 첨부 시점의 엔딩 요약(엔딩 종류, 업종, 상권, 가게·사장님 이름, 생존 분기, 누적 순이익)을 글에 스냅샷으로 복사해 업종·상권 필터와 정렬에 쓴다 |

- 게임 연결 지점: 엔딩 화면 → "엔딩 인증하기"(`/board/write?category=RECORD&gameId=…`), 인증글 카드 → `/share/{gameId}`, 공유 페이지 → 인증글 링크, 홈 → 인기글 미리보기.
- 랭킹 행 → 인증글 연결은 랭킹 응답 변경이 필요해 후속.

### 규칙 (DB와 서비스가 같이 지킨다)
| 규칙 | 서비스 | DB |
| --- | --- | --- |
| RECORD는 본인 소유 + ENDED 게임만 | 없음·남의 게임 404(존재 노출 금지), 진행 중 409 | 트리거 `trg_posts_record_game_guard` |
| 게임당 삭제되지 않은(ACTIVE·HIDDEN) RECORD 1건, DELETED 후 재작성 허용 | 409 `RECORD_ALREADY_POSTED` | 부분 유일 인덱스 `ux_posts_record_game` |
| 스냅샷은 서버가 채움 (요청은 `gameId`만) | 위조 방지 | CHECK `ck_posts_record_snapshot` |
| 대댓글은 1단계까지, 같은 글의 ACTIVE 루트에만 | 부모 없음·다른 글·비공개 404, 대댓글을 부모로 지정 400 `COMMENT_DEPTH_EXCEEDED` | 트리거 `trg_comments_depth_guard` |
| 좋아요 사용자당 1회, PUT/DELETE 멱등 | 실제 삽입·삭제 행 수로 카운터 갱신 | UNIQUE(post_id, user_id) |
| 신고 사용자당 대상별 1회, 자기 글 신고 금지 | 409 / 403 | UNIQUE(target_type, target_id, reporter_id) |
| 수정·삭제는 작성자만, 동시 수정은 낙관적 락 | 403, 409 `POST_VERSION_CONFLICT` | `version` 컬럼 (카운터 갱신은 version을 올리지 않음) |
| 삭제는 soft delete, 숨김은 관리자만 | 상태 `ACTIVE/HIDDEN/DELETED` | CHECK로 `deleted_at`과 상태 일치 |
| 조회수는 유효 토큰 사용자만 KST 하루 1회 | 신규 삽입 성공 시에만 +1 | `post_views` PK(post_id, user_id, viewed_on) |

- 검색: 한국어 형태소 분석기가 없어 `tsvector` 대신 `ILIKE '%키워드%'` + `pg_trgm` GIN 인덱스. postgis/postgis:16-3.5에서 pg_trgm 1.6 가용, 인덱스 사용 확인(EXPLAIN).
- 숨김·삭제된 루트 댓글은 공개 답글이 남아 있을 때만 "표시할 수 없는 댓글입니다." 자리표시자로 남긴다.

### 화면 기술
- 사용자 게시판: **React + REST** (게임과 같은 SPA, `X-Guest-Token` 헤더 인증을 그대로 사용. Thymeleaf 폼은 커스텀 헤더를 보낼 수 없어 쿠키 브리지가 따로 필요해짐).
- 관리자 게시판 `/admin/board/**`: **Thymeleaf + Spring Security 세션**. 폼 바인딩, `BindingResult`, CSRF, PRG(303 + 플래시)를 여기서 보여준다.
- 공유 페이지 `/share/{gameId}`: Thymeleaf, 로그인 없이 공개.

### 교차 리뷰로 확정한 것 (2026-11-18)
- 판정 순서 `401 → 400 → 404 → 403 → 409` 고정, 비공개 대상에는 좋아요·댓글·신고 불가(404), 관리자 숨김·복구 시 카운터 재계산, LIKE 이스케이프(`\`, `%`, `_`), 게시판 쓰기 레이트 리밋(429). 자세한 규칙은 API 4장 "판정 순서와 안전 규칙".
- 동시 대댓글 교착: 깊이 트리거가 부모를 `FOR SHARE`로 잠그면 두 사람이 같은 댓글에 동시에 답글을 달 때 `deadlock detected`가 실제로 재현됐다. `FOR KEY SHARE`로 바꿔 두 답글 모두 성공, `reply_count` 2를 확인했다.
- Flyway 순서: V11은 V8~V10보다 번호가 크므로, V8~V10이 병합되기 전에는 전용 DB에서만 적용하고 main 병합은 그 뒤에 한다(`outOfOrder`는 켜지 않는다).

### 패키지
`com.seah.startupsim.board` (controller, service, repository, dto). game 패키지는 수정하지 않고, 인증은 `GuestAuth.resolve()`에 위임, RECORD 원본은 읽기 전용 조회 포트(`BoardRecordReadRepository`)로만 읽는다.

### 범위
| 기말 (MVP) | 이후 |
| --- | --- |
| 목록(페이징·검색·정렬·필터), 상세(조회수), 작성·수정(낙관적 락)·삭제(soft) | 이미지 업로드, 리치 에디터 |
| 댓글 + 1단계 대댓글, 좋아요, 신고, 관리자 숨김·복구·고정 | 금칙어, 자동 블라인드, 알림 |
| RECORD 인증글 + 공유 페이지 연결 (엔딩 요약 구현 후) | 랭킹 → 인증글 연결, 동네 반응 답글(선택 코드 기록으로 결정론 유지) |
