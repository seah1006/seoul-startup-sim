package com.seah.startupsim.ingest;

/** 출처별 수집 계약이다. 실행 이력과 원본 저장은 공통 프레임워크에 맡겨 수집 로직을 분리한다. */
public interface Collector {
    String source();

    CollectResult collect(IngestContext context);
}
