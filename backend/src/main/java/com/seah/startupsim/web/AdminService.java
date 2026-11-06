package com.seah.startupsim.web;

import com.seah.startupsim.common.SecretMasker;
import com.seah.startupsim.ingest.Collector;
import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 조회 결과의 비밀 마스킹과 출처별 운영 지표를 조합한다. */
@Service
public class AdminService {
    private final AdminRepository repository;
    private final List<String> sources;
    private final SecretMasker masker;
    private final SeoulOpenApiClient client;
    public AdminService(AdminRepository repository, List<Collector> collectors, SecretMasker masker, SeoulOpenApiClient client) {
        this.repository = repository;
        this.sources = collectors.stream().map(Collector::source).sorted().toList();
        this.masker = masker; this.client = client;
    }
    public List<String> sources() { return sources; }
    public List<AdminRepository.Summary> dashboard() { return sources.stream().map(repository::summary).toList(); }
    public String circuit() { return client.circuitBreakerState(); }
    public List<AdminRepository.Run> runs(String source, String status, int page) {
        if (page < 0 || page > 1000000) throw new IllegalArgumentException("페이지 범위 오류");
        return repository.runs(source, status, page);
    }
    public AdminRepository.Run run(long id) {
        AdminRepository.Run run = repository.run(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new AdminRepository.Run(run.id(), run.source(), run.period(), run.status(), run.fetched(),
                run.inserted(), run.updated(), run.failed(), masker.mask(run.error()), run.startedAt(), run.finishedAt());
    }
    public List<AdminRepository.Raw> raws(long id) {
        return repository.raws(id).stream().map(raw -> new AdminRepository.Raw(raw.id(),
                masker.mask(raw.requestKey()), raw.size(), raw.checksum())).toList();
    }
    public String raw(long id, long rawId) {
        String raw = repository.raw(id, rawId);
        if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return masker.mask(raw);
    }
    public List<AdminRepository.Version> versions() { return repository.versions(); }
}
