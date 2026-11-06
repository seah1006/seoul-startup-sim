package com.seah.startupsim.ingest;
public class IngestAlreadyRunningException extends RuntimeException {
 public IngestAlreadyRunningException(String source){super("이미 실행 중인 source: "+source);}
}