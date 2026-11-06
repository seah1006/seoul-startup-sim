package com.seah.startupsim.catalog.store;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** saveAll 후 flush/clear하여 영속성 컨텍스트를 배치 크기로 제한한다. */
@Component
public class JpaStoreBulkLoader implements StoreBulkLoader {
    private final StoreJpaRepository repository;
    private final EntityManager entityManager;
    public JpaStoreBulkLoader(StoreJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }
    @Transactional
    public void load(List<StoreRow> rows, String snapshot, Long versionId) {
        repository.saveAll(rows.stream().map(r -> new StoreEntity(r, snapshot, versionId)).toList());
        repository.flush();
        entityManager.clear();
    }
}
