package com.seah.startupsim.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.external")
public class ExternalProperties {
    private final Seoul seoul = new Seoul();
    private final Datagokr datagokr = new Datagokr();

    public Seoul getSeoul() {
        return seoul;
    }

    public Datagokr getDatagokr() {
        return datagokr;
    }

    public static class Seoul {
        private String apiKey = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String value) {
            apiKey = value;
        }
    }

    public static class Datagokr {
        private String serviceKey = "";

        public String getServiceKey() {
            return serviceKey;
        }

        public void setServiceKey(String value) {
            serviceKey = value;
        }
    }
}
