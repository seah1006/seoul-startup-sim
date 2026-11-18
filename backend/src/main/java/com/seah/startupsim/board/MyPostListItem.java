package com.seah.startupsim.board;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record MyPostListItem(long postId, PostCategory category, String title, String authorNickname,
        Instant createdAt, Instant updatedAt, long viewCount, int likeCount, int commentCount,
        @JsonProperty("isPinned") boolean isPinned, RecordSnapshot record, String status, long version) {}
