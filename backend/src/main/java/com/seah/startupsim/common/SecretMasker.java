package com.seah.startupsim.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class SecretMasker {
    private final ExternalProperties properties;
    public SecretMasker(ExternalProperties properties) { this.properties = properties; }

    public String mask(String text) {
        if (text == null) { return ""; }
        String result = text;
        for (String key : new String[]{properties.getSeoul().getApiKey(), properties.getDatagokr().getServiceKey()}) {
            if (key != null && !key.isBlank()) {
                result = result.replace(key, "****");
                result = result.replace(URLEncoder.encode(key, StandardCharsets.UTF_8), "****");
            }
        }
        result = result.replaceAll("(?i)([?&](?:serviceKey|key)=)[^&#\\s]+", "$1****");
        // 서울 Open API URL: /인증키/json(또는 xml)/서비스명/...
        result = result.replaceAll("(?i)(https?://[^/\\s]+(?::[0-9]+)?/)[^/\\s]+(/(?:json|xml)/)", "$1****$2");
        return result;
    }
}