package com.seah.startupsim.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateCommentRequest(
        @NotBlank @Size(max = 1000) @JsonDeserialize(using = BoardJson.Text.class) String content,
        @Positive @JsonDeserialize(using = BoardJson.IntegerValue.class) Long parentId) {
    public CreateCommentRequest {
        content = content == null ? null : content.strip();
    }
}
