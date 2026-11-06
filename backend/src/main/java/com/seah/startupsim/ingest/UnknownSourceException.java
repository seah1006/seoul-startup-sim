package com.seah.startupsim.ingest;
public class UnknownSourceException extends RuntimeException {
 public UnknownSourceException(String source){super("등록되지 않은 source: "+source);}
}