package com.seah.startupsim.area;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** bbox 공간 인덱스 조회와 줌별 표시 단순화를 제공한다. */
@Service
public class AreaService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AreaService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public Map<String, Object> areas(String bbox, int zoom) {
        double[] b;
        try { b = java.util.Arrays.stream(bbox.split(",", -1)).mapToDouble(Double::parseDouble).toArray(); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("bbox는 숫자 네 개여야 합니다."); }
        if (b.length != 4 || java.util.Arrays.stream(b).anyMatch(v -> !Double.isFinite(v))
                || b[0] >= b[2] || b[1] >= b[3] || b[0] < -180 || b[2] > 180 || b[1] < -90 || b[3] > 90 || zoom < 0 || zoom > 22) {
            throw new IllegalArgumentException("bbox 또는 zoom 범위가 올바르지 않습니다.");
        }
        double tolerance = zoom < 11 ? .001 : zoom < 14 ? .0002 : zoom < 16 ? .00005 : 0;
        List<Map<String, Object>> features = jdbc.query("""
                SELECT area_code,name,area_type_name,district_name,
                ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom,?)) AS geometry
                FROM trade_areas WHERE ST_Intersects(geom,ST_MakeEnvelope(?,?,?,?,4326))
                ORDER BY area_code LIMIT 501
                """, (rs, n) -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    for (String key : List.of("area_code", "name", "area_type_name", "district_name")) properties.put(key, rs.getString(key));
                    return Map.<String, Object>of("type", "Feature", "properties", properties, "geometry", mapper.readTree(rs.getString("geometry")));
                }, tolerance, b[0], b[1], b[2], b[3]);
        boolean truncated = features.size() > 500;
        return Map.of("type", "FeatureCollection", "features", new ArrayList<>(features.subList(0, Math.min(500, features.size()))), "truncated", truncated, "limit", 500);
    }
    public Map<String, Object> detail(String code) {
        List<Map<String, Object>> result = jdbc.queryForList("""
                SELECT area_code,name,area_type_code,area_type_name,district_code,district_name,dong_code,dong_name,area_m2,
                ST_X(centroid) AS longitude,ST_Y(centroid) AS latitude,version_id FROM trade_areas WHERE area_code=?
                """, code);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상권을 찾을 수 없습니다.");
        return result.getFirst();
    }
    public List<Map<String, Object>> search(String q) {
        if (q.isBlank() || q.length() > 100) throw new IllegalArgumentException("검색어는 1~100자여야 합니다.");
        String escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbc.queryForList("SELECT area_code,name,area_type_name,district_name FROM trade_areas WHERE name LIKE ? ESCAPE '!' ORDER BY name,area_code LIMIT 20", "%" + escaped + "%");
    }
}
