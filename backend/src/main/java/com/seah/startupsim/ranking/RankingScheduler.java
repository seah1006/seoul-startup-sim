package com.seah.startupsim.ranking;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 30분마다와 시작 직후 1회 랭킹을 갱신한다. 테스트는 이 빈을 끄고 배치를 직접 호출한다. */
@Component
@ConditionalOnProperty(name = "app.ranking.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class RankingScheduler {
    private final RankingBatch batch;
    private final Clock clock;

    public RankingScheduler(RankingBatch batch, Clock clock) {
        this.batch = batch;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        batch.refresh(clock.instant());
    }

    @Scheduled(cron = "0 0,30 * * * *", zone = "Asia/Seoul")
    public void every30Minutes() {
        batch.refresh(clock.instant());
    }
}
