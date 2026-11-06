package com.seah.startupsim.ingest;

import com.seah.startupsim.common.SecretMasker;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 분기별 실행을 격리하여 한 분기의 실패가 다음 분기를 막지 않게 한다. */
@Service
public class QuarterBackfillService {
    private static final Set<String> SOURCES = Set.of("seoul-area-sales", "seoul-area-stores", "seoul-area-floating-pop");
    private final IngestService service;
    private final SecretMasker masker;

    public QuarterBackfillService(IngestService service, SecretMasker masker) {
        this.service = service;
        this.masker = masker;
    }

    public record Result(String quarter, Long runId, String status, String error) {}

    public List<Result> backfill(String source, String fromQuarter, String toQuarter) {
        if (!SOURCES.contains(source)) throw new UnknownSourceException(source);
        validate(fromQuarter);
        validate(toQuarter);
        if (fromQuarter.compareTo(toQuarter) > 0) throw new IllegalArgumentException("분기 범위 역전");
        List<Result> results = new ArrayList<>();
        for (String quarter = fromQuarter; quarter.compareTo(toQuarter) <= 0; quarter = next(quarter)) {
            try {
                results.add(new Result(quarter, service.run(source, quarter), "SUCCEEDED", null));
            } catch (RuntimeException e) {
                results.add(new Result(quarter, null, "FAILED", masker.mask(e.getMessage())));
            }
        }
        return results;
    }

    public static void validate(String quarter) {
        if (quarter == null || !quarter.matches("[0-9]{4}[1-4]")) throw new IllegalArgumentException("분기는 YYYYQ 형식이어야 합니다.");
    }

    private static String next(String quarter) {
        int year = Integer.parseInt(quarter.substring(0, 4));
        int q = quarter.charAt(4) - '0';
        return q == 4 ? String.format("%04d1", year + 1) : quarter.substring(0, 4) + (q + 1);
    }
}
