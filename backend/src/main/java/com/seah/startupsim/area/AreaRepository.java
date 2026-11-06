package com.seah.startupsim.area;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 상권 적재와 버전 활성화를 수집기 트랜잭션 안에서 수행한다. */
@Repository
public class AreaRepository {
    private final JdbcTemplate jdbc;
    public AreaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long version(String source, int rows) {
        return jdbc.queryForObject("INSERT INTO data_versions(source,status,row_count) VALUES (?,'READY',?) RETURNING id", Long.class, source, rows);
    }

    public void activate(String source, long id) {
        jdbc.update("UPDATE data_versions SET status='READY' WHERE source=? AND status='ACTIVE'", source);
        jdbc.update("UPDATE data_versions SET status='ACTIVE',activated_at=now() WHERE id=?", id);
    }

    public boolean exists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM trade_areas WHERE area_code=?)", Boolean.class, code));
    }

    public void shape(ShapeReader.Row row, long version) {
        var f = row.fields();
        jdbc.update("""
                WITH shape AS (SELECT ST_Multi(ST_Transform(ST_SetSRID(ST_GeomFromWKB(?),5181),4326)) AS g)
                INSERT INTO trade_areas(area_code,name,area_type_code,area_type_name,district_code,district_name,dong_code,dong_name,area_m2,geom,centroid,version_id)
                SELECT ?,?,?,?,?,?,?,?,?::numeric,g,ST_Centroid(g),? FROM shape
                ON CONFLICT(area_code) DO UPDATE SET geom=excluded.geom,centroid=excluded.centroid,version_id=excluded.version_id
                """, row.wkb(), f.get("TRDAR_CD"), f.get("TRDAR_CD_N"), f.get("TRDAR_SE_C"), f.get("TRDAR_SE_1"),
                f.get("SIGNGU_CD"), f.get("SIGNGU_CD_"), f.get("ADSTRD_CD"), f.get("ADSTRD_CD_"), f.get("RELM_AR"), version);
    }

    public void meta(tools.jackson.databind.JsonNode row, long version) {
        String code = row.path("TRDAR_CD").asText();
        if (!exists(code)) throw new IllegalStateException("SHP 적재가 먼저 필요합니다: " + code);
        jdbc.update("""
                INSERT INTO trade_areas(area_code,name,area_type_code,area_type_name,district_code,district_name,dong_code,dong_name,area_m2,geom,centroid,version_id)
                SELECT area_code,?,?,?,?,?,?,?,?::numeric,geom,centroid,? FROM trade_areas WHERE area_code=?
                ON CONFLICT(area_code) DO UPDATE SET name=excluded.name,area_type_code=excluded.area_type_code,
                area_type_name=excluded.area_type_name,district_code=excluded.district_code,district_name=excluded.district_name,
                dong_code=excluded.dong_code,dong_name=excluded.dong_name,area_m2=excluded.area_m2,version_id=excluded.version_id
                """, row.path("TRDAR_CD_NM").asText(), row.path("TRDAR_SE_CD").asText(), row.path("TRDAR_SE_CD_NM").asText(),
                row.path("SIGNGU_CD").asText(), row.path("SIGNGU_CD_NM").asText(), row.path("ADSTRD_CD").asText(), row.path("ADSTRD_CD_NM").asText(),
                row.path("RELM_AR").asText(), version, code);
    }
}
