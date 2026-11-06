package com.seah.startupsim.ingest;

/** 수집 실행 식별자와 원본 저장 기능을 제공하여 수집기가 저장 구현에 직접 의존하지 않게 한다. */
public final class IngestContext {
    private final long runId;
    private final String source;
    private final String targetPeriod;
    private final IngestRepository repository;

    IngestContext(long runId, String source, String targetPeriod, IngestRepository repository) {
        this.runId = runId;
        this.source = source;
        this.targetPeriod = targetPeriod;
        this.repository = repository;
    }

    public long runId() {
        return runId;
    }

    public String targetPeriod() {
        return targetPeriod;
    }

    public void saveRaw(String requestKey, String jsonPayload) {
        repository.saveRaw(runId, source, requestKey, jsonPayload);
    }
}
