package com.seah.startupsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.seah.startupsim.common.ExternalProperties;
import com.seah.startupsim.common.SecretMasker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretMaskerTest {
    @Test
    @DisplayName("설정된 인증키와 URL 인코딩된 인증키를 가린다")
    void configuredSecretsAreMasked() {
        ExternalProperties p = new ExternalProperties();
        p.getSeoul().setApiKey("dummy-seoul");
        p.getDatagokr().setServiceKey("dummy+portal");
        assertEquals(
                "**** **** ****",
                new SecretMasker(p).mask("dummy-seoul dummy+portal dummy%2Bportal"));
    }

    @Test
    @DisplayName("미등록 키도 쿼리와 서울 API 경로에서 가린다")
    void queryAndSeoulPathAreMaskedWithoutConfiguredKeys() {
        SecretMasker masker = new SecretMasker(new ExternalProperties());
        assertEquals(
                "failed"
                    + " http://openapi.seoul.go.kr:8088/****/json/service?serviceKey=****&key=****&page=2",
                masker.mask(
                        "failed"
                            + " http://openapi.seoul.go.kr:8088/unknown/json/service?serviceKey=hidden&key=other&page=2"));
    }
}
