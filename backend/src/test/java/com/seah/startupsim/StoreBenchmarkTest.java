package com.seah.startupsim;

import com.seah.startupsim.catalog.store.CopyStoreBulkLoader;
import com.seah.startupsim.catalog.store.JdbcStoreBulkLoader;
import com.seah.startupsim.catalog.store.JpaStoreBulkLoader;
import com.seah.startupsim.catalog.store.StoreBulkLoader;
import com.seah.startupsim.catalog.store.StoreCsvParser;
import com.seah.startupsim.ingest.IngestService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Tag("benchmark")
@SpringBootTest(properties = "app.ingest.sbiz-csv=${sbiz.csv}")
class StoreBenchmarkTest {
    @Autowired CopyStoreBulkLoader copy;
    @Autowired JdbcStoreBulkLoader batch;
    @Autowired JpaStoreBulkLoader jpa;
    @Autowired StoreCsvParser parser;
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestService ingest;
    @Test
    void measure() {
        Path file = Path.of(System.getProperty("sbiz.csv"));
        for (int size : new int[]{10000,100000,552422}) {
            for (StoreBulkLoader loader : List.of(copy,batch,jpa)) {
                for (int run=0;run<4;run++) {
                    jdbc.execute("TRUNCATE stores,store_snapshot_diffs");
                    long start=System.nanoTime();
                    parser.read(file,size,rows -> loader.load(rows,"202609",null));
                    double seconds=(System.nanoTime()-start)/1e9;
                    System.out.printf("BENCH,%s,%s,%d,%d,%.3f,%.1f%n",Instant.now(),loader.getClass().getSimpleName(),size,run,seconds,size/seconds);
                }
            }
        }
        long start=System.nanoTime();
        long id=ingest.run("sbiz-stores-csv","202609");
        System.out.println("COLLECTOR,"+id+","+(System.nanoTime()-start)/1e9+","+jdbc.queryForObject("SELECT count(*) FROM stores",Long.class));
        jdbc.execute("ANALYZE stores");
        System.out.println(jdbc.queryForList("""
                EXPLAIN ANALYZE SELECT count(*) FROM stores
                WHERE small_code='I21201'
                  AND geom && ST_Expand(ST_SetSRID(ST_MakePoint(126.905,37.556),4326),0.004)
                  AND ST_DWithin(geom::geography,ST_SetSRID(ST_MakePoint(126.905,37.556),4326)::geography,300)
                """,String.class));
    }
}
