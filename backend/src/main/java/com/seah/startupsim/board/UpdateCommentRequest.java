package com.seah.startupsim.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateCommentRequest(
        @NotBlank @Size(max = 1000) @JsonDeserialize(using = BoardJson.Text.class) String content,
        @NotNull @PositiveOrZero @JsonDeserialize(using = BoardJson.IntegerValue.class) Long version) {
    public UpdateCommentRequest {
        content = content == null ? null : content.strip();
    }
}
