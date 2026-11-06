package com.seah.startupsim.ingest;
public final class IngestContext {
 private final long runId;
 private final String source;
 private final String targetPeriod;
 private final IngestRepository repository;
 IngestContext(long runId,String source,String targetPeriod,IngestRepository repository){
  this.runId=runId;this.source=source;this.targetPeriod=targetPeriod;this.repository=repository;
 }
 public long runId(){return runId;}
 public String targetPeriod(){return targetPeriod;}
 public void saveRaw(String requestKey,String jsonPayload){repository.saveRaw(runId,source,requestKey,jsonPayload);}
}