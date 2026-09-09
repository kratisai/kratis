package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record SteerCommentDto(String path, Integer line, String codeSnippet, String comment) {
    public SteerCommentDto {
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(comment, "comment is required");
    }
}
