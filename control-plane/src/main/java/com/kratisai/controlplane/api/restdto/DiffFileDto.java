package com.kratisai.controlplane.api.restdto;

import java.util.Objects;

public record DiffFileDto(String path, String patch, int additions, int deletions, int totalLines) {
    public DiffFileDto {
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(patch, "patch is required");
    }
}
