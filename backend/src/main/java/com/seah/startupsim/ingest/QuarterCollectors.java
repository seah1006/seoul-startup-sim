package com.seah.startupsim.ingest;

import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;

/** 서울 분기 데이터 3종의 필드 매핑을 명시적으로 등록한다. */
@Configuration
public class QuarterCollectors {
    @Bean
    Collector salesCollector(SeoulOpenApiClient client, JdbcTemplate jdbc, PlatformTransactionManager tx) {
        return new QuarterlyCollector("seoul-area-sales", "VwsmTrdarSelngQq", "area_sales_q",
                true, List.of(new QuarterlyCollector.Field("sales_amount", "THSMON_SELNG_AMT"),
                        new QuarterlyCollector.Field("sales_count", "THSMON_SELNG_CO"),
                        new QuarterlyCollector.Field("weekday_amount", "MDWK_SELNG_AMT"),
                        new QuarterlyCollector.Field("weekend_amount", "WKEND_SELNG_AMT"),
                        new QuarterlyCollector.Field("morning_amount", "TMZON_06_11_SELNG_AMT"),
                        new QuarterlyCollector.Field("lunch_amount", "TMZON_11_14_SELNG_AMT"),
                        new QuarterlyCollector.Field("evening_amount", "TMZON_17_21_SELNG_AMT"),
                        new QuarterlyCollector.Field("male_amount", "ML_SELNG_AMT"),
                        new QuarterlyCollector.Field("female_amount", "FML_SELNG_AMT")), client, jdbc, tx);
    }

    @Bean
    Collector storesCollector(SeoulOpenApiClient client, JdbcTemplate jdbc, PlatformTransactionManager tx) {
        return new QuarterlyCollector("seoul-area-stores", "VwsmTrdarStorQq", "area_stores_q",
                true, List.of(new QuarterlyCollector.Field("store_count", "SIMILR_INDUTY_STOR_CO"),
                        new QuarterlyCollector.Field("general_count", "STOR_CO"),
                        new QuarterlyCollector.Field("franchise_count", "FRC_STOR_CO"),
                        new QuarterlyCollector.Field("opening_rate", "OPBIZ_RT"),
                        new QuarterlyCollector.Field("opening_count", "OPBIZ_STOR_CO"),
                        new QuarterlyCollector.Field("closing_rate", "CLSBIZ_RT"),
                        new QuarterlyCollector.Field("closing_count", "CLSBIZ_STOR_CO")), client, jdbc, tx);
    }

    @Bean
    Collector floatingpopCollector(SeoulOpenApiClient client, JdbcTemplate jdbc, PlatformTransactionManager tx) {
        return new QuarterlyCollector("seoul-area-floating-pop", "VwsmTrdarFlpopQq", "area_floating_pop_q",
                false, List.of(new QuarterlyCollector.Field("total_population", "TOT_FLPOP_CO"),
                        new QuarterlyCollector.Field("male_population", "ML_FLPOP_CO"),
                        new QuarterlyCollector.Field("female_population", "FML_FLPOP_CO"),
                        new QuarterlyCollector.Field("morning_population", "TMZON_06_11_FLPOP_CO"),
                        new QuarterlyCollector.Field("lunch_population", "TMZON_11_14_FLPOP_CO"),
                        new QuarterlyCollector.Field("evening_population", "TMZON_17_21_FLPOP_CO")), client, jdbc, tx);
    }
}
