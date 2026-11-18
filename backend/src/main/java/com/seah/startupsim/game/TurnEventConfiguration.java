package com.seah.startupsim.game;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 이벤트 엔진이 없는 동안 외부 동작을 바꾸지 않는 기본 구현을 제공한다. */
@Configuration(proxyBeanMethods = false)
public class TurnEventConfiguration {
    @Bean
    @ConditionalOnMissingBean(TurnEventHook.class)
    TurnEventHook noOpTurnEventHook() {
        return (context, outcome, result) -> {};
    }
}
