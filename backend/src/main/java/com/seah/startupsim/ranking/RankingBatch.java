package com.seah.startupsim.ranking;

import com.seah.startupsim.game.GameRecordRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 날짜별 스냅샷을 한 트랜잭션에서 지우고 다시 계산한다. 트랜잭션 advisory lock으로 동시 실행은 하나만 한다. */
@Service
public class RankingBatch {
    /** 랭킹 갱신 전용 advisory lock 키. 다른 잠금과 겹치지 않게 고정값을 쓴다. */
    public static final long LOCK_KEY = 4_004_200_009L;
    public static final int TOP = 100;
    private static final Logger log = LoggerFactory.getLogger(RankingBatch.class);

    public record Refresh(boolean executed, List<LocalDate> dates, Instant refreshedAt) {}

    private final JdbcTemplate jdbc;
    private final AtomicReference<Refresh> last = new AtomicReference<>();

    public RankingBatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 갱신 대상은 KST 오늘이고, 00시대 실행이면 자정 직전 엔딩을 반영하려고 어제도 다시 계산한다. */
    public static List<LocalDate> targetDates(Instant now) {
        ZonedDateTime kst = now.atZone(GameRecordRepository.KST);
        List<LocalDate> dates = new ArrayList<>(List.of(kst.toLocalDate()));
        if (kst.getHour() == 0) {
            dates.add(kst.toLocalDate().minusDays(1));
        }
        return List.copyOf(dates);
    }

    @Transactional
    public Refresh refresh(Instant now) {
        List<LocalDate> dates = targetDates(now);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY))) {
            log.info("다른 랭킹 갱신이 실행 중이라 건너뜁니다.");
            return new Refresh(false, dates, null);
        }
        for (LocalDate date : dates) {
            jdbc.update("DELETE FROM ranking_snapshots WHERE ranking_date = ?", Date.valueOf(date));
            // 파티션별 RANK() 공동 순위(1, 1, 3). 상위 100위까지 저장하므로 100위 동점자는 모두 남는다.
            jdbc.update("""
                    INSERT INTO ranking_snapshots (ranking_date, kind, key, rank, game_id, refreshed_at)
                    SELECT ranking_date, kind, key, rank, game_id, ? FROM (
                        SELECT r.ranking_date, k.kind, k.key, r.game_id,
                               RANK() OVER (PARTITION BY k.kind, k.key ORDER BY r.survived_quarters DESC,
                                   r.total_profit DESC, r.best_quarter_profit DESC, r.shop_count DESC) AS rank
                        FROM game_records r
                        CROSS JOIN LATERAL (VALUES ('ALL', ''), ('INDUSTRY', r.service_code), ('AREA', r.area_code))
                            AS k(kind, key)
                        WHERE r.ranking_date = ?
                    ) ranked
                    WHERE rank <= ?
                    """, Timestamp.from(now), Date.valueOf(date), TOP);
        }
        Refresh refresh = new Refresh(true, dates, now);
        last.set(refresh);
        log.info("랭킹을 갱신했습니다: {}", dates);
        return refresh;
    }

    /** 이 인스턴스에서 마지막으로 실제 실행된 갱신. 재시작 뒤에는 스냅샷 테이블의 시각을 쓴다. */
    public Refresh last() {
        return last.get();
    }
}
