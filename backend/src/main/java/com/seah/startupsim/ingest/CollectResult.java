package com.seah.startupsim.ingest;
public record CollectResult(int fetched,int inserted,int updated,int failed) {
 public CollectResult {
  if(fetched<0 || inserted<0 || updated<0 || failed<0) throw new IllegalArgumentException("수집 건수는 음수일 수 없습니다.");
 }
}