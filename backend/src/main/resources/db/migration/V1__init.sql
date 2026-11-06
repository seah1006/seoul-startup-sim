-- 공간 데이터 저장에 필요한 PostGIS 확장.
CREATE EXTENSION IF NOT EXISTS postgis;

-- 출처별 데이터 버전과 활성화 상태.
CREATE TABLE data_versions (
    id bigserial PRIMARY KEY,
    source varchar(50) NOT NULL,
    period varchar(20),
    status varchar(20) NOT NULL CHECK (status IN ('LOADING', 'READY', 'ACTIVE', 'FAILED')),
    row_count bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz
);

-- 같은 출처의 ACTIVE 버전은 하나만 허용한다.
CREATE UNIQUE INDEX data_versions_one_active
    ON data_versions(source) WHERE status = 'ACTIVE';

-- 수집 실행 이력은 성공과 실패 모두 보존한다.
CREATE TABLE ingest_runs (
    id bigserial PRIMARY KEY,
    source varchar(50) NOT NULL,
    target_period varchar(20),
    status varchar(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    fetched int NOT NULL DEFAULT 0,
    inserted int NOT NULL DEFAULT 0,
    updated int NOT NULL DEFAULT 0,
    failed int NOT NULL DEFAULT 0,
    error_message text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE INDEX ingest_runs_source_started ON ingest_runs(source, started_at DESC);

-- 부분 유니크 인덱스로 다중 인스턴스의 중복 수집도 원자적으로 거절한다.
CREATE UNIQUE INDEX ingest_runs_one_running
    ON ingest_runs(source) WHERE status = 'RUNNING';

-- 재처리와 장애 분석을 위한 외부 응답 원본.
CREATE TABLE raw_payloads (
    id bigserial PRIMARY KEY,
    run_id bigint NOT NULL REFERENCES ingest_runs(id),
    source varchar(50) NOT NULL,
    request_key varchar(500) NOT NULL,
    payload jsonb NOT NULL,
    checksum char(64) NOT NULL,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    -- 같은 실행에서 같은 요청의 원본을 중복 저장하지 않는다.
    UNIQUE (run_id, request_key)
);