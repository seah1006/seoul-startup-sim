package com.seah.startupsim.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 강제 종료된 수집을 복구할 기준 시간이다. 정상적인 장시간 수집보다 길게 설정한다. */
@ConfigurationProperties("app.ingest")
public record IngestProperties(@DefaultValue("2h") Duration staleAfter) {
    public IngestProperties {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("app.ingest.stale-after는 양수여야 합니다.");
        }
    }
}
