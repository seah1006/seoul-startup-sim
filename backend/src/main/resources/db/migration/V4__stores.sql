CREATE TABLE stores (
    store_id varchar(30) PRIMARY KEY,
    name text, branch_name text, large_code text, middle_code text,
    small_code text, small_name text, std_industry_code text,
    district_code text, dong_code text, road_address text,
    geom geometry(Point,4326) NOT NULL,
    snapshot varchar(20) NOT NULL,
    version_id bigint REFERENCES data_versions(id)
);
CREATE INDEX stores_geom_idx ON stores USING gist(geom);
CREATE INDEX stores_small_code_idx ON stores(small_code);
CREATE INDEX stores_dong_code_idx ON stores(dong_code);
CREATE TABLE store_snapshot_diffs (
    snapshot varchar(20) NOT NULL,
    store_id varchar(30) NOT NULL,
    change_type varchar(10) NOT NULL CHECK(change_type IN ('NEW','CLOSED','CHANGED')),
    PRIMARY KEY(snapshot, store_id)
);
