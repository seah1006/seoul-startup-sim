package com.seah.startupsim;
import com.seah.startupsim.common.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SecretMaskerTest {
 @Test void configuredSecretsAreMasked(){
  ExternalProperties p=new ExternalProperties();
  p.getSeoul().setApiKey("dummy-seoul");p.getDatagokr().setServiceKey("dummy+portal");
  assertEquals("**** **** ****",new SecretMasker(p).mask("dummy-seoul dummy+portal dummy%2Bportal"));
 }
 @Test void queryAndSeoulPathAreMaskedWithoutConfiguredKeys(){
  SecretMasker masker=new SecretMasker(new ExternalProperties());
  assertEquals("failed http://openapi.seoul.go.kr:8088/****/json/service?serviceKey=****&key=****&page=2",masker.mask("failed http://openapi.seoul.go.kr:8088/unknown/json/service?serviceKey=hidden&key=other&page=2"));
 }
}