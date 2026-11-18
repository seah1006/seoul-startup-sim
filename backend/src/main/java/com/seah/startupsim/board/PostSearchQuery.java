package com.seah.startupsim.board;

import org.springframework.data.domain.PageRequest;

public record PostSearchQuery(PageRequest page, PostCategory category, Sort sort, SearchType searchType,
        String q, String serviceCode, String areaCode) {
    enum Sort {
        latest("p.created_at DESC"), popular("p.like_count DESC, p.created_at DESC"),
        views("p.view_count DESC, p.created_at DESC"), comments("p.comment_count DESC, p.created_at DESC");

        final String sql;
        Sort(String sql) { this.sql = sql; }
    }

    enum SearchType { title, content, titleContent, author }

    static PostSearchQuery of(Integer page, Integer size, PostCategory category, Sort sort,
            SearchType searchType, String q, String serviceCode, String areaCode) {
        var pagination = BoardPagination.of(page, size);
        serviceCode = code(serviceCode);
        areaCode = code(areaCode);
        if (serviceCode != null || areaCode != null) {
            if (category != null && category != PostCategory.RECORD) {
                throw BoardException.invalid();
            }
            category = PostCategory.RECORD;
        }
        q = q == null ? null : q.strip();
        if (q != null && (q.length() > 100 || q.indexOf(0) >= 0)) {
            throw BoardException.invalid();
        }
        return new PostSearchQuery(pagination, category,
                sort == null ? Sort.latest : sort, searchType == null ? SearchType.titleContent : searchType,
                q == null || q.isEmpty() ? null : q, serviceCode, areaCode);
    }

    private static String code(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > 32 || trimmed.indexOf(0) >= 0) {
            throw BoardException.invalid();
        }
        return trimmed;
    }
}
