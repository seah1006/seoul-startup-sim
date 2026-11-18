package com.seah.startupsim.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 관리자·공유 화면(Thymeleaf)용 한글 표기. 템플릿에서 {@code ${@labels.time(run.startedAt)}}처럼 쓴다.
 * 모르는 값은 원래 코드를 그대로 보여 준다(새 상태가 추가돼도 화면이 깨지지 않게).
 */
@Component("labels")
public class DisplayLabels {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final Map<String, String> ENDINGS = Map.of("CLOSED", "폐업", "SOLD", "매각",
            "LONG_RUNNING", "장수 가게");
    private static final Map<String, String> CIRCUIT = Map.of("CLOSED", "정상", "OPEN", "차단",
            "HALF_OPEN", "시험 중", "DISABLED", "비활성", "FORCED_OPEN", "강제 차단", "METRICS_ONLY", "측정만");
    private static final Map<String, String> RUN = Map.of("RUNNING", "실행 중", "SUCCEEDED", "성공", "FAILED", "실패");
    private static final Map<String, String> VERSION = Map.of("LOADING", "적재 중", "READY", "준비됨",
            "ACTIVE", "사용 중", "FAILED", "실패");
    private static final Map<String, String> POST_STATUS = Map.of("ACTIVE", "공개", "HIDDEN", "숨김",
            "DELETED", "삭제");
    private static final Map<String, String> CATEGORY = Map.of("FREE", "자유", "TIP", "공략", "QNA", "질문",
            "RECORD", "엔딩 인증");
    private static final Map<String, String> REPORT_STATUS = Map.of("PENDING", "대기", "RESOLVED", "처리 완료",
            "DISMISSED", "기각");
    private static final Map<String, String> REASON = Map.of("SPAM", "광고·스팸", "ABUSE", "욕설·괴롭힘",
            "ADULT", "성인 콘텐츠", "ETC", "기타");
    private static final Map<String, String> TARGET = Map.of("POST", "게시글", "COMMENT", "댓글");

    /** UTC 시각을 "2026-11-07 02:17 (KST)"로. 값이 없으면 "-". */
    public String time(Object value) {
        ZonedDateTime time = switch (value) {
            case null -> null;
            case Instant instant -> instant.atZone(KST);
            case OffsetDateTime offset -> offset.atZoneSameInstant(KST);
            case ZonedDateTime zoned -> zoned.withZoneSameInstant(KST);
            case java.util.Date date -> date.toInstant().atZone(KST);
            default -> throw new IllegalArgumentException("시각 값이 아닙니다: " + value.getClass().getSimpleName());
        };
        return time == null ? "-" : MINUTES.format(time) + " (KST)";
    }

    public String circuit(Object state) {
        return label(CIRCUIT, state);
    }

    public String runStatus(Object status) {
        return label(RUN, status);
    }

    public String versionStatus(Object status) {
        return label(VERSION, status);
    }

    public String ending(Object type) {
        return label(ENDINGS, type);
    }

    public String postStatus(Object status) {
        return label(POST_STATUS, status);
    }

    public String category(Object category) {
        return label(CATEGORY, category);
    }

    public String reportStatus(Object status) {
        return label(REPORT_STATUS, status);
    }

    public String reason(Object reason) {
        return label(REASON, reason);
    }

    public String target(Object type) {
        return label(TARGET, type);
    }

    private static String label(Map<String, String> labels, Object value) {
        if (value == null) {
            return "-";
        }
        String code = value instanceof Enum<?> constant ? constant.name() : value.toString();
        return labels.getOrDefault(code, code);
    }
}