package com.seah.startupsim.board;

import org.springframework.data.domain.PageRequest;

final class BoardPagination {
    private BoardPagination() {}

    static PageRequest of(Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 50) throw BoardException.invalid();
        return PageRequest.of(pageNumber, pageSize);
    }
}
