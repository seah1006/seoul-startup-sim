package com.seah.startupsim.board;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record PostDetail(long postId, PostCategory category, String title, String authorNickname,
        Instant createdAt, Instant updatedAt, long viewCount, int likeCount, int commentCount,
        @JsonProperty("isPinned") boolean isPinned, RecordSnapshot record, String content, long version,
        boolean likedByMe, @JsonProperty("isMine") boolean isMine) {}
