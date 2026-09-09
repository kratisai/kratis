package com.kratisai.controlplane.api.restdto;

import java.util.List;
import java.util.Objects;

public record ReadFileSliceDto(String path, int startLine, List<String> lines) {
    public ReadFileSliceDto {
        Objects.requireNonNull(path, "path is required");
        lines = lines != null ? List.copyOf(lines) : List.of();
    }
}
