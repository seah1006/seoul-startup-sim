package com.seah.startupsim.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(@NotNull PostCategory category,
        @NotBlank @Size(max = 100) String title, @NotBlank @Size(max = 10000) String content) {
    public CreatePostRequest {
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
    }
}
