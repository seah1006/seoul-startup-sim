package com.seah.startupsim.ingest.client;

import com.seah.startupsim.common.ExternalProperties;
import com.seah.startupsim.common.SecretMasker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** HTTP 200 내부 오류까지 분류하고 모든 실제 요청에 속도 제한과 장애 차단을 적용한다. */
@Component
public class SeoulOpenApiClient {
    private static final Logger log = LoggerFactory.getLogger(SeoulOpenApiClient.class);
    private final SeoulClientProperties settings;
    private final ExternalProperties external;
    private final SecretMasker masker;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final Retry retry;
    private final RateLimiter limiter;
    private final CircuitBreaker breaker;

    public SeoulOpenApiClient(SeoulClientProperties settings, ExternalProperties external, SecretMasker masker) {
        this.settings = settings;
        this.external = external;
        this.masker = masker;
        http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
        retry = Retry.of("seoul", RetryConfig.custom().maxAttempts(settings.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(settings.retryWait().toMillis(), 2))
                .retryExceptions(RetryableSeoulApiException.class).build());
        limiter = RateLimiter.of("seoul", RateLimiterConfig.custom()
                .limitForPeriod(settings.requestsPerSecond()).limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(30)).build());
        breaker = CircuitBreaker.of("seoul", CircuitBreakerConfig.custom()
                .slidingWindowSize(settings.failureThreshold()).minimumNumberOfCalls(settings.failureThreshold())
                .failureRateThreshold(100).waitDurationInOpenState(settings.openWait())
                .recordExceptions(RetryableSeoulApiException.class)
                .ignoreExceptions(PermanentSeoulApiException.class).build());
        retry.getEventPublisher().onRetry(e -> log.warn("서울 API 재시도 {}: {}", e.getNumberOfRetryAttempts(), masker.mask(e.getLastThrowable().getMessage())));
    }

    public record ApiPage(int start, int end, String raw, JsonNode rows) {}

    public void pages(String service, String period, Consumer<ApiPage> consumer) {
        for (int start = 1; ; start += 1000) {
            final int first = start;
            JsonNode root;
            String raw;
            try {
                raw = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker,
                        RateLimiter.decorateSupplier(limiter, () -> request(service, period, first)))).get();
                root = mapper.readTree(raw);
            } catch (CallNotPermittedException e) {
                throw new SeoulApiException("circuit open");
            }
            JsonNode body = root.path(service);
            String code = body.path("RESULT").path("CODE").asText(root.path("RESULT").path("CODE").asText());
            JsonNode rows = body.path("row");
            consumer.accept(new ApiPage(start, start + 999, raw, rows));
            if ("INFO-200".equals(code)) return;
            int total = body.path("list_total_count").asInt(-1);
            if (total < 0 || !rows.isArray() || (rows.isEmpty() && start <= total)) {
                throw new PermanentSeoulApiException("잘못된 서울 API 페이지 구조");
            }
            if (start + rows.size() - 1 >= total) return;
            if (rows.size() != 1000) throw new PermanentSeoulApiException("서울 API 중간 페이지 행 수 불일치");
        }
    }

    private String request(String service, String period, int start) {
        String key = external.getSeoul().getApiKey();
        URI uri = URI.create(settings.baseUrl() + "/" + encode(key) + "/json/" + encode(service)
                + "/" + start + "/" + (start + 999) + "/" + encode(period));
        log.info("서울 API 요청 {}", masker.mask(uri.toString()));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(settings.readTimeout()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500 || response.statusCode() == 429) {
                throw new RetryableSeoulApiException("HTTP " + response.statusCode());
            }
            if (response.statusCode() != 200) throw new PermanentSeoulApiException("HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            String code = root.path(service).path("RESULT").path("CODE")
                    .asText(root.path("RESULT").path("CODE").asText());
            if ("ERROR-500".equals(code) || "ERROR-600".equals(code) || "ERROR-601".equals(code)) {
                throw new RetryableSeoulApiException("서울 API " + code);
            }
            if (!"INFO-000".equals(code) && !"INFO-200".equals(code)) {
                throw new PermanentSeoulApiException("서울 API " + masker.mask(code));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PermanentSeoulApiException("서울 API 요청 중단");
        } catch (IOException e) {
            throw new RetryableSeoulApiException(masker.mask("서울 API 통신 실패: " + e.getMessage()));
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
