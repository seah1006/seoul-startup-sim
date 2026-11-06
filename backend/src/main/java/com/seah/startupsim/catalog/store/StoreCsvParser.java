package com.seah.startupsim.catalog.store;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PushbackReader;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** UTF-8 CSV를 최대 1,000행씩 읽는다. 잘못된 좌표만 실패 건수로 집계하고 건너뛴다. */
@Component
public class StoreCsvParser {
    public record Counts(int fetched, int failed) {}

    public Counts read(Path file, int limit, Consumer<List<StoreRow>> consumer) {
        int fetched = 0;
        int failed = 0;
        List<StoreRow> batch = new ArrayList<>(1000);
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(file, StandardCharsets.UTF_8), 1)) {
            List<String> header = next(reader);
            List<String> values;
            while ((values = next(reader)) != null) {
                if (values.size() != header.size()) throw new IOException("CSV 컬럼 수 불일치");
                Map<String,String> r = new HashMap<>();
                for (int i=0;i<header.size();i++) r.put(header.get(i), values.get(i));
                if (fetched >= limit) break;
                fetched++;
                double lng;
                double lat;
                try {
                    lng = Double.parseDouble(r.get("경도"));
                    lat = Double.parseDouble(r.get("위도"));
                    if (!Double.isFinite(lng) || !Double.isFinite(lat)
                            || lng < -180 || lng > 180 || lat < -90 || lat > 90) {
                        throw new NumberFormatException("좌표 범위 오류");
                    }
                } catch (NumberFormatException e) {
                    failed++;
                    continue;
                }
                batch.add(new StoreRow(r.get("상가업소번호"), r.get("상호명"), r.get("지점명"),
                        r.get("상권업종대분류코드"), r.get("상권업종중분류코드"),
                        r.get("상권업종소분류코드"), r.get("상권업종소분류명"),
                        r.get("표준산업분류코드"), r.get("시군구코드"), r.get("행정동코드"),
                        r.get("도로명주소"), lng, lat));
                if (batch.size() == 1000) {
                    consumer.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) consumer.accept(batch);
            return new Counts(fetched, failed);
        } catch (IOException e) {
            throw new IllegalStateException("CSV 읽기 실패", e);
        }
    }
    /** RFC 4180의 따옴표, 이중 따옴표, 셀 내 줄바꿈과 CRLF를 처리한다. */
    private List<String> next(PushbackReader reader) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean seen = false;
        int c;
        while ((c = reader.read()) != -1) {
            seen = true;
            if (c == '"') {
                if (quoted) {
                    int following = reader.read();
                    if (following == '"') cell.append('"');
                    else {
                        quoted = false;
                        if (following != -1) reader.unread(following);
                    }
                } else if (cell.isEmpty()) quoted = true;
                else throw new IOException("잘못된 CSV 따옴표");
            } else if (!quoted && c == ',') {
                fields.add(cell.toString());
                cell.setLength(0);
            } else if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r') {
                    int following = reader.read();
                    if (following != '\n' && following != -1) reader.unread(following);
                }
                fields.add(cell.toString());
                return fields;
            } else cell.append((char)c);
        }
        if (quoted) throw new IOException("닫히지 않은 CSV 따옴표");
        if (!seen) return null;
        fields.add(cell.toString());
        return fields;
    }
}
