package com.seah.startupsim.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.seah.startupsim.ingest.QuarterBackfillService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 활성 분기 리포트와 자치구 매출 추정, 공간 인덱스 점포 조회를 제공한다. */
@Service
public class CatalogService {
    private final JdbcTemplate jdbc;
    public CatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> industries(Boolean playable) {
        return jdbc.queryForList("SELECT service_code AS \"serviceCode\",name,category,(category='FOOD' AND has_sales) AS playable FROM service_industries WHERE (?::boolean IS NULL OR (category='FOOD' AND has_sales)=?) ORDER BY service_code", playable, playable);
    }
    private String areaName(String code) {
        List<String> names = jdbc.queryForList("SELECT name FROM trade_areas WHERE area_code=?", String.class, code);
        if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"상권을 찾을 수 없습니다.");
        return names.getFirst();
    }
    public Map<String,Object> report(String code, String quarter) {
        String name = areaName(code);
        if (quarter == null) quarter = jdbc.queryForObject("SELECT max(period) FROM data_versions WHERE source='seoul-area-sales' AND status='ACTIVE'", String.class);
        if (quarter == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"활성 분기가 없습니다.");
        QuarterBackfillService.validate(quarter);
        List<Map<String,Object>> industries = jdbc.queryForList("""
                WITH district AS (
                    SELECT x.service_code, sum(x.sales_amount)::numeric / nullif(sum(t.store_count),0) AS per_store
                    FROM area_sales_q x JOIN area_stores_q t USING(quarter,area_code,service_code)
                    JOIN trade_areas a USING(area_code)
                    WHERE x.quarter=? AND x.sales_amount IS NOT NULL AND t.store_count>0
                    AND a.district_code=(SELECT district_code FROM trade_areas WHERE area_code=?)
                    GROUP BY x.service_code
                )
                SELECT i.service_code AS "serviceCode",i.name,coalesce(t.store_count,0) AS "storeCount",
                    coalesce(t.franchise_count,0) AS "franchiseCount",t.opening_rate AS "openingRatePct",
                    t.closing_rate AS "closingRatePct",
                    coalesce(x.sales_amount,round(d.per_store*t.store_count)::bigint) AS "quarterSales",
                    (x.sales_amount IS NULL AND d.per_store*t.store_count IS NOT NULL) AS "salesEstimated"
                FROM service_industries i
                LEFT JOIN area_stores_q t ON t.service_code=i.service_code AND t.area_code=? AND t.quarter=?
                LEFT JOIN area_sales_q x ON x.service_code=i.service_code AND x.area_code=? AND x.quarter=?
                LEFT JOIN district d ON d.service_code=i.service_code
                WHERE t.area_code IS NOT NULL OR x.area_code IS NOT NULL ORDER BY i.service_code
                """, quarter,code,code,quarter,code,quarter);
        List<Long> population = jdbc.queryForList("SELECT total_population FROM area_floating_pop_q WHERE area_code=? AND quarter=?",Long.class,code,quarter);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("areaCode",code); result.put("name",name); result.put("quarter",quarter);
        result.put("floatingPopulation",population.isEmpty()?null:population.getFirst()); result.put("industries",industries);
        return result;
    }
    public Map<String,Object> stores(String code, String serviceCode, double lng, double lat, int radius) {
        if (!Double.isFinite(lng) || !Double.isFinite(lat) || lng < -180 || lng > 180 || lat < -90 || lat > 90 || radius < 50 || radius > 1000) throw new IllegalArgumentException("좌표 또는 반경(50~1000m)이 올바르지 않습니다.");
        areaName(code);
        List<Map<String,Object>> stores = jdbc.queryForList("""
                WITH point AS (SELECT ST_SetSRID(ST_MakePoint(?,?),4326) AS g)
                SELECT s.store_id AS "storeId",s.name,ST_X(s.geom) AS lng,ST_Y(s.geom) AS lat,
                    ST_Distance(s.geom::geography,p.g::geography) AS "distanceM"
                FROM stores s CROSS JOIN point p
                WHERE s.geom && ST_Envelope(ST_Buffer(p.g::geography,?)::geometry)
                    AND ST_DWithin(s.geom::geography,p.g::geography,?)
                    AND (?::text IS NULL OR s.service_code=?)
                ORDER BY "distanceM",s.store_id LIMIT 100
                """,lng,lat,radius,radius,serviceCode,serviceCode);
        return Map.of("count",stores.size(),"stores",stores);
    }
}

