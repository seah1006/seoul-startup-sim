package com.seah.startupsim.ingest;

import com.seah.startupsim.common.SecretMasker;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 수집 실행의 시작과 결과를 관리한다. RUNNING 부분 유니크 인덱스로 인스턴스 간 동시 실행을 거절한다. */
@Service
public class IngestService {
    private final Map<String, Collector> collectors;
    private final IngestRepository repository;
    private final SecretMasker masker;

    public IngestService(
            List<Collector> collectors, IngestRepository repository, SecretMasker masker) {
        this.collectors =
                collectors.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        Collector::source, Function.identity()));
        this.repository = repository;
        this.masker = masker;
    }

    public long run(String source, String period) {
        if (source == null
                || source.isBlank()
                || source.length() > 50
                || (period != null && period.length() > 20)) {
            throw new IllegalArgumentException("source는 1~50자, 기간은 20자 이하여야 합니다.");
        }
        Collector collector = collectors.get(source);
        if (collector == null) {
            throw new UnknownSourceException(source);
        }
        long id;
        try {
            id = repository.start(source, period);
        } catch (DuplicateKeyException e) {
            throw new IngestAlreadyRunningException(source);
        }
        try {
            CollectResult result =
                    collector.collect(new IngestContext(id, source, period, repository));
            repository.succeed(id, result);
            return id;
        } catch (RuntimeException | Error e) {
            // 마스킹을 먼저 수행한 후 최대 길이를 제한한다.
            String message = masker.mask(e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                repository.fail(id, message.substring(0, Math.min(1000, message.length())));
            } catch (RuntimeException failure) {
                e.addSuppressed(failure);
            }
            throw e;
        }
    }
}
