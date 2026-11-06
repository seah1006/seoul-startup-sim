package com.seah.startupsim.ingest.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/** 서울 API의 속도와 장애 복구 정책이다. 인증키는 기존 ExternalProperties에서 읽는다. */
@ConfigurationProperties("app.external.seoul")
public record SeoulClientProperties(
        @DefaultValue("http://openapi.seoul.go.kr:8088") String baseUrl,
        @DefaultValue("5") int requestsPerSecond,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration retryWait,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("5") int failureThreshold,
        @DefaultValue("30s") Duration openWait) {
    public SeoulClientProperties {
        if (requestsPerSecond < 1 || maxAttempts < 1 || failureThreshold < 1
                || retryWait.isNegative() || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero() || openWait.isNegative()) {
            throw new IllegalArgumentException("서울 API 정책 설정값이 잘못되었습니다.");
        }
    }
}
