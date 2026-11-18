package com.seah.startupsim.board;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateReportRequest(
        @NotNull @Pattern(regexp = "POST|COMMENT") @JsonDeserialize(using = BoardJson.Text.class) String targetType,
        @NotNull @Positive @JsonDeserialize(using = BoardJson.IntegerValue.class) Long targetId,
        @NotNull @Pattern(regexp = "SPAM|ABUSE|ADULT|ETC") @JsonDeserialize(using = BoardJson.Text.class) String reason,
        @Size(max = 500) @JsonDeserialize(using = BoardJson.Text.class) String detail) {}
