# 004B 파트 2 초안: 엔딩, 기록, 랭킹, 공유 (메인 에이전트)

## 1. 엔딩 (MVP 3종)
| 엔딩 | 판정 위치 | 조건 |
| --- | --- | --- |
| CLOSED | advance 트랜잭션 (현재 코드 유지) | 잔액이 2분기 연속 음수 |
| LONG_RUNNING | advance 트랜잭션 (현재 코드 유지) | 진행한 분기가 `last_quarter` |
| SOLD | `POST /api/games/{id}/sell` | 플레이어 선택. 최소 4분기 영업 후 가능(그 전엔 400) |

- FRANCHISE_HQ는 후속(17장). 같은 분기에 엔딩과 전환점이 겹치면 엔딩 우선, 전환점은 만들지 않는다. 전환점 대기 중 매각 요청은 409 `TURNING_POINT_PENDING`.
- **매각 가치**: `max(0, 최근 4분기 평균 순이익) × settings['sell.quarters'](기본 4)`. 반올림 원 단위. 원장에 `SALE` 항목으로 기록, 잔액에 더한 뒤 ENDED/SOLD. `Idempotency-Key` 필수(기존 idempotency_keys 재사용), 게임 행 잠금 + version.
- 엔딩이 나는 모든 경로(advance, sell)는 **같은 트랜잭션에서** `game_records`를 1행 쓴다(PK game_id라 중복 불가). 커밋 뒤 리스너에 맡기지 않는 이유: 기록 누락(리스너 실패) 시 랭킹·요약·인증글 원본이 사라지기 때문. `GameEndedEvent`는 004A 계약대로 발행하되 커밋 후 처리(랭킹 즉시 갱신 요청 등 부가 작업)만.

## 2. V9 스키마 초안
```sql
CREATE TABLE game_records (
  game_id bigint PRIMARY KEY REFERENCES games(id),
  user_id bigint NOT NULL REFERENCES users(id),
  nickname text NOT NULL,                -- 엔딩 시점 닉네임 스냅샷
  ending_type text NOT NULL CHECK (ending_type IN ('CLOSED','LONG_RUNNING','SOLD')),
  survived_quarters int NOT NULL CHECK (survived_quarters >= 0),
  total_profit bigint NOT NULL,
  best_quarter_profit bigint NOT NULL,
  shop_count int NOT NULL CHECK (shop_count >= 1),
  sale_value bigint,                     -- SOLD만
  area_code text NOT NULL, area_name text NOT NULL,
  service_code text NOT NULL, service_name text NOT NULL,
  shop_name text NOT NULL, owner_name text NOT NULL,
  ended_at timestamptz NOT NULL DEFAULT now(),
  ranking_date date NOT NULL,            -- (ended_at AT TIME ZONE 'Asia/Seoul')::date, 서비스에서 계산해 넣음
  CONSTRAINT ck_game_records_sale CHECK ((ending_type = 'SOLD') = (sale_value IS NOT NULL))
);
CREATE INDEX ix_game_records_rank ON game_records (ranking_date, survived_quarters DESC, total_profit DESC, best_quarter_profit DESC, shop_count DESC);
CREATE INDEX ix_game_records_user ON game_records (user_id, ended_at DESC);

CREATE TABLE ranking_snapshots (
  ranking_date date NOT NULL,
  kind text NOT NULL CHECK (kind IN ('ALL','INDUSTRY','AREA')),
  key text NOT NULL DEFAULT '',          -- ALL은 '', INDUSTRY는 service_code, AREA는 area_code
  rank int NOT NULL CHECK (rank >= 1),
  game_id bigint NOT NULL REFERENCES game_records(game_id),
  refreshed_at timestamptz NOT NULL,
  PRIMARY KEY (ranking_date, kind, key, game_id)
);
CREATE INDEX ix_ranking_snapshots_read ON ranking_snapshots (ranking_date, kind, key, rank);
INSERT INTO game_settings(key,value) VALUES ('sell.quarters','4'), ('sell.minQuarters','4') ON CONFLICT DO NOTHING;
```
- 9개 스냅샷 필드(엔딩, 업종·상권 코드/이름, 가게·사장님 이름, 생존 분기, 누적 순이익)가 여기 다 있으므로 **TASK-005B의 RECORD 원본은 `game_records`** 로 확정된다.

## 3. 요약 API (계약 3장 그대로 + 원본 명시)
- `GET /api/games/{id}/summary` 소유자만, ENDED 아니면 409 `GAME_NOT_ENDED`(신규 코드). `game_records`에서 읽는다. `shareUrl: "/share/{id}"`.

## 4. 랭킹 배치
- `@Scheduled(cron = "0 0,30 * * * *", zone = "Asia/Seoul")` + 앱 시작 직후 1회. 갱신 대상: 오늘(KST), 그리고 00:00~00:59 사이 실행이면 어제도(자정 직전 엔딩 반영).
- 한 트랜잭션: `pg_try_advisory_xact_lock(해시)` 실패 시 건너뜀(중복 실행 방지) → 해당 날짜 스냅샷 DELETE → INSERT … SELECT로 3종(ALL, INDUSTRY별, AREA별) 계산.
- 순위: `RANK() OVER (PARTITION BY … ORDER BY survived_quarters DESC, total_profit DESC, best_quarter_profit DESC, shop_count DESC)` → 공동 순위(1, 1, 3). 파티션별 상위 100까지만 저장.
- 읽기 `GET /api/rankings?date&kind&key&limit`: date 기본 오늘, **오늘 포함 최근 7일만**(밖이면 400), kind 기본 ALL, INDUSTRY/AREA는 key 필수, limit 기본 50 최대 100. 응답 `refreshedAt`은 스냅샷 시각. 계약 3장 entries에 **`gameId` 추가**(후속 랭킹→인증글 연결 대비, 하위 호환).
- 캐시는 스냅샷 테이블 자체. 별도 캐시 계층은 두지 않는다(단일 인스턴스, 읽기 쿼리 인덱스 1회).
- 관리자: `/admin/rankings`에 마지막 갱신 시각과 "지금 갱신" 버튼(같은 잠금 사용).

## 5. 공유 페이지
- `GET /share/{gameId}` Thymeleaf, **`web` 패키지**에 둔다(game 패키지 밖이라 TASK-005B가 인증글 링크를 붙일 때 game 패키지를 안 건드려도 된다). 로그인 불필요.
- `game_records` 없으면 404 HTML. OG: `og:title`("{가게} · {엔딩}"), `og:description`(생존 분기, 누적 순이익), `og:image`(정적 기본 이미지). 모두 `th:content`/`th:text`.
- 게스트 토큰·잔액 상세·결정 기록 같은 비공개 정보는 넣지 않는다.

## 6. 테스트 요점
- 엔딩 3경로 각각 game_records 정확히 1행, advance 재시도(같은 키) 시 중복 없음, 매각 같은 키 재요청 1회만 정산
- 매각: 4분기 미만 400, 대기 전환점 409, 평균 음수면 0원
- 랭킹: 동점 공동 순위, 정렬 키 4단계, KST 자정 경계(23:50 엔딩은 그날, 00:10은 다음 날), 동시 배치 2개 중 1개만 실행, 7일 밖 400, INDUSTRY key 누락 400
- 공유: 404, OG 이스케이프(`<script>` 가게 이름), 비공개 정보 없음
