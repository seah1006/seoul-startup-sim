package com.seah.startupsim.web;

import com.seah.startupsim.board.PostCategory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayLabelsTest {
    private final DisplayLabels labels = new DisplayLabels();

    @Test
    @DisplayName("UTC 시각은 KST 분 단위 \"2026-11-07 02:17 (KST)\"로, 없으면 -")
    void time() {
        assertEquals("2026-11-07 02:17 (KST)", labels.time(Instant.parse("2026-11-06T17:17:01.136608Z")));
        assertEquals("2026-11-07 02:17 (KST)", labels.time(OffsetDateTime.of(2026, 11, 6, 17, 17, 59, 0, ZoneOffset.UTC)));
        assertEquals("2026-01-01 08:59 (KST)", labels.time(java.sql.Timestamp.from(Instant.parse("2025-12-31T23:59:00Z"))));
        assertEquals("-", labels.time(null));
    }

    @Test
    @DisplayName("서킷 브레이커·수집·버전·엔딩·게시판 코드는 한글 라벨, 모르는 값은 코드 그대로")
    void enums() {
        assertEquals("정상", labels.circuit("CLOSED"));
        assertEquals("차단", labels.circuit("OPEN"));
        assertEquals("시험 중", labels.circuit("HALF_OPEN"));
        assertEquals("성공", labels.runStatus("SUCCEEDED"));
        assertEquals("실행 중", labels.runStatus("RUNNING"));
        assertEquals("사용 중", labels.versionStatus("ACTIVE"));
        assertEquals("장수 가게", labels.ending("LONG_RUNNING"));
        assertEquals("매각", labels.ending("SOLD"));
        assertEquals("폐업", labels.ending("CLOSED"));
        assertEquals("엔딩 인증", labels.category(PostCategory.RECORD));
        assertEquals("숨김", labels.postStatus("HIDDEN"));
        assertEquals("기각", labels.reportStatus("DISMISSED"));
        assertEquals("광고·스팸", labels.reason("SPAM"));
        assertEquals("댓글", labels.target("COMMENT"));
        assertEquals("NEW_STATE", labels.circuit("NEW_STATE"));
        assertEquals("-", labels.ending(null));
    }
}
