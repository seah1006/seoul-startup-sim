package com.seah.startupsim.area;

import com.seah.startupsim.ingest.Collector;
import com.seah.startupsim.ingest.CollectResult;
import com.seah.startupsim.ingest.IngestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** SHP는 파일 메타만 보존하며 좌표 변환은 PostGIS에 위임한다. */
@Component
public class ShapeCollector implements Collector {
    private final AreaRepository repository;
    private final String path;
    private final ObjectMapper mapper;
    public ShapeCollector(AreaRepository repository, ObjectMapper mapper,
            @Value("${app.ingest.area-shapefile:../data/raw/area/trdar.shp}") String path) {
        this.repository = repository; this.mapper = mapper; this.path = path;
    }
    public String source() { return "seoul-area-shapes"; }
    @Transactional
    public CollectResult collect(IngestContext context) {
        try {
            Path file = Path.of(path);
            var rows = new ShapeReader().read(file);
            context.saveRaw("shapefile", mapper.writeValueAsString(Map.of("filename", file.getFileName().toString(),
                    "size", Files.size(file), "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))), "rows", rows.size())));
            long version = repository.version(source(), rows.size());
            int inserted = 0;
            for (var row : rows) {
                if (!repository.exists(row.fields().get("TRDAR_CD"))) inserted++;
                repository.shape(row, version);
            }
            repository.activate(source(), version);
            return new CollectResult(rows.size(), inserted, rows.size() - inserted, 0);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("상권 SHP 읽기 실패: " + path, e);
        }
    }
}
