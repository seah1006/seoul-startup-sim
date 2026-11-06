package com.seah.startupsim.ingest;
public interface Collector {
 String source();
 CollectResult collect(IngestContext context);
}