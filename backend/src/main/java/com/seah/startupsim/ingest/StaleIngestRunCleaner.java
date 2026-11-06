package com.seah.startupsim.ingest;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 재시작 시 오래된 RUNNING을 정리하여 부분 유니크 인덱스가 새 수집을 영구 차단하지 않게 한다. */
@Component
public class StaleIngestRunCleaner {
    private final IngestRepository repository;
    private final IngestProperties properties;

    public StaleIngestRunCleaner(IngestRepository repository, IngestProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        Instant cutoff = Instant.now().minus(properties.staleAfter());
        repository.failStaleRuns(cutoff);
    }
}
