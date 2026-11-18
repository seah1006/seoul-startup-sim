package com.seah.startupsim.board;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdatePostRequest(@NotNull PostCategory category,
        @NotBlank @Size(max = 100) String title, @NotBlank @Size(max = 10000) String content,
        @NotNull @PositiveOrZero Long version) {
    public UpdatePostRequest {
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
    }

    @JsonAnySetter
    public void rejectAttachmentChange(String field, Object ignored) {
        if ("gameId".equals(field)) {
            throw new IllegalArgumentException("첨부 게임은 변경할 수 없습니다.");
        }
    }
}
