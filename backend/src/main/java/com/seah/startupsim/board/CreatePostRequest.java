package com.seah.startupsim.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreatePostRequest(@NotNull @JsonDeserialize(using = BoardJson.Category.class) PostCategory category,
        @NotBlank @Size(max = 100) @JsonDeserialize(using = BoardJson.Text.class) String title,
        @NotBlank @Size(max = 10000) @JsonDeserialize(using = BoardJson.Text.class) String content) {
    public CreatePostRequest {
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
    }
}
