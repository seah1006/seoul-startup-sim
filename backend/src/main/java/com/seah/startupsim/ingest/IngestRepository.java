package com.seah.startupsim.ingest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
@Repository
public class IngestRepository {
 private final JdbcTemplate jdbc;
 public IngestRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public long start(String source,String period){
  return jdbc.queryForObject("INSERT INTO ingest_runs(source,target_period,status) VALUES (?,?,'RUNNING') RETURNING id",Long.class,source,period);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void succeed(long id,CollectResult r){
  jdbc.update("UPDATE ingest_runs SET status='SUCCEEDED',fetched=?,inserted=?,updated=?,failed=?,finished_at=now() WHERE id=? AND status='RUNNING'",r.fetched(),r.inserted(),r.updated(),r.failed(),id);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void fail(long id,String message){
  jdbc.update("UPDATE ingest_runs SET status='FAILED',error_message=?,finished_at=now() WHERE id=? AND status='RUNNING'",message,id);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void saveRaw(long id,String source,String key,String payload){
  if(key==null || key.isBlank() || key.length()>500) throw new IllegalArgumentException("requestKey는 1~500자여야 합니다.");
  try {
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
   jdbc.update("INSERT INTO raw_payloads(run_id,source,request_key,payload,checksum) VALUES (?,?,?,CAST(? AS jsonb),?)",id,source,key,payload,hash);
  }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 사용 불가",e);}
 }
}