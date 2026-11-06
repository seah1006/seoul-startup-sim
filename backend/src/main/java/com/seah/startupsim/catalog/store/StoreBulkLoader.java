package com.seah.startupsim.catalog.store;

import java.util.List;

/** 제한된 크기의 배치로 입력을 받아 동일한 점포 upsert 계약을 제공한다. */
public interface StoreBulkLoader {
    void load(List<StoreRow> rows, String snapshot, Long versionId);
}
