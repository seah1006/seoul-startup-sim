package com.seah.startupsim.board;

import java.util.List;
import org.springframework.data.domain.Pageable;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, long totalPages) {
    static <T> PageResponse<T> of(List<T> content, Pageable pageable, long total) {
        int size = pageable.getPageSize();
        return new PageResponse<>(content, pageable.getPageNumber(), size, total,
                total / size + (total % size == 0 ? 0 : 1));
    }
}
