-- 출처+분기별 활성 버전. 기간 없는 출처의 기존 유일성도 유지한다.
DROP INDEX data_versions_one_active;
CREATE UNIQUE INDEX data_versions_one_active ON data_versions(source, (coalesce(period, ''))) WHERE status='ACTIVE';
CREATE TABLE area_sales_q (
    quarter char(5) NOT NULL CHECK (quarter ~ '^[0-9]{4}[1-4]$'),
    area_code varchar(10) NOT NULL,
    service_code varchar(10) NOT NULL,
    sales_amount bigint,
    sales_count bigint,
    weekday_amount bigint,
    weekend_amount bigint,
    morning_amount bigint,
    lunch_amount bigint,
    evening_amount bigint,
    male_amount bigint,
    female_amount bigint,
    version_id bigint NOT NULL REFERENCES data_versions(id),
    PRIMARY KEY (quarter, area_code, service_code)
);
COMMENT ON COLUMN area_sales_q.sales_amount IS '분기 3개월 합계(원), DATA_NOTES A-2';
CREATE TABLE area_stores_q (
    quarter char(5) NOT NULL CHECK (quarter ~ '^[0-9]{4}[1-4]$'),
    area_code varchar(10) NOT NULL,
    service_code varchar(10) NOT NULL,
    store_count bigint,
    general_count bigint,
    franchise_count bigint,
    opening_rate bigint,
    opening_count bigint,
    closing_rate bigint,
    closing_count bigint,
    version_id bigint NOT NULL REFERENCES data_versions(id),
    PRIMARY KEY (quarter, area_code, service_code)
);
CREATE TABLE area_floating_pop_q (
    quarter char(5) NOT NULL CHECK (quarter ~ '^[0-9]{4}[1-4]$'),
    area_code varchar(10) NOT NULL,
    total_population bigint,
    male_population bigint,
    female_population bigint,
    morning_population bigint,
    lunch_population bigint,
    evening_population bigint,
    version_id bigint NOT NULL REFERENCES data_versions(id),
    PRIMARY KEY (quarter, area_code)
);
COMMENT ON COLUMN area_floating_pop_q.total_population IS '단위 미확인(DATA_NOTES): 상대 비교용';
COMMENT ON COLUMN area_floating_pop_q.male_population IS '단위 미확인(DATA_NOTES): 상대 비교용';
COMMENT ON COLUMN area_floating_pop_q.female_population IS '단위 미확인(DATA_NOTES): 상대 비교용';
COMMENT ON COLUMN area_floating_pop_q.morning_population IS '단위 미확인(DATA_NOTES): 상대 비교용';
COMMENT ON COLUMN area_floating_pop_q.lunch_population IS '단위 미확인(DATA_NOTES): 상대 비교용';
COMMENT ON COLUMN area_floating_pop_q.evening_population IS '단위 미확인(DATA_NOTES): 상대 비교용';
