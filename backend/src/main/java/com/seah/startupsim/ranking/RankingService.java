package com.seah.startupsim.ranking;

import com.seah.startupsim.game.GameRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** 조회 범위(최근 7일, key, limit)를 검증하고 스냅샷만 읽는다. 캐시는 스냅샷 테이블 자체다. */
@Service
public class RankingService {
    public static final int DAYS = 7;
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public record Ranking(LocalDate date, OffsetDateTime refreshedAt, RankingKind kind,
            List<RankingRepository.Entry> entries) {}

    private final RankingRepository repository;
    private final RankingBatch batch;
    private final Clock clock;

    public RankingService(RankingRepository repository, RankingBatch batch, Clock clock) {
        this.repository = repository;
        this.batch = batch;
        this.clock = clock;
    }

    public LocalDate today() {
        return GameRecordRepository.rankingDate(clock.instant());
    }

    public Ranking ranking(LocalDate date, RankingKind kind, String key, Integer limit) {
        LocalDate today = today();
        LocalDate target = date == null ? today : date;
        if (target.isAfter(today) || target.isBefore(today.minusDays(DAYS - 1))) {
            throw new IllegalArgumentException("랭킹은 오늘 포함 최근 7일만 조회할 수 있습니다.");
        }
        RankingKind resolvedKind = kind == null ? RankingKind.ALL : kind;
        String resolvedKey = resolvedKind == RankingKind.ALL || key == null ? "" : key.strip();
        if (resolvedKind != RankingKind.ALL && (resolvedKey.isEmpty() || resolvedKey.length() > 32)) {
            throw new IllegalArgumentException("업종·상권 랭킹은 key가 필요합니다.");
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new IllegalArgumentException("limit은 1~100입니다.");
        }
        return new Ranking(target, kst(refreshedAt(target)), resolvedKind,
                repository.entries(target, resolvedKind, resolvedKey, size));
    }

    /** 기록이 없는 날은 스냅샷 행이 없으므로, 그 날짜를 갱신한 이 인스턴스의 마지막 실행 시각을 쓴다. */
    private Instant refreshedAt(LocalDate date) {
        Instant stored = repository.refreshedAt(date);
        if (stored != null) {
            return stored;
        }
        RankingBatch.Refresh last = batch.last();
        return last != null && last.dates().contains(date) ? last.refreshedAt() : null;
    }

    private static OffsetDateTime kst(Instant instant) {
        return instant == null ? null : instant.atZone(GameRecordRepository.KST).toOffsetDateTime();
    }
}
