-- V9__endings_ranking.sql : 엔딩 기록, 랭킹 스냅샷 (TASK-004B 파트 2)
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
