package com.kratisai.controlplane.api.restdto;

import java.util.List;

public record SteerExecutionRequest(String prompt, List<SteerCommentDto> comments) {
    public SteerExecutionRequest {
        comments = comments != null ? List.copyOf(comments) : List.of();
    }
}
