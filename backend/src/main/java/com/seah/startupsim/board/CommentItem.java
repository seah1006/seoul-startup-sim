package com.seah.startupsim.board;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record CommentItem(long commentId, Long parentId, String authorNickname, String content,
        Instant createdAt, Instant updatedAt, Long version, int replyCount,
        @JsonProperty("isMine") boolean isMine, @JsonProperty("isPlaceholder") boolean isPlaceholder) {}
