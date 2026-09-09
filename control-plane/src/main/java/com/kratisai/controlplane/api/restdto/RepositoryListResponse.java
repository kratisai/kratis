package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "List of repositories with total count")
public record RepositoryListResponse(
        @Schema(description = "List of repositories") List<RepositoryDto> repositories,

        @Schema(description = "Total number of repositories")
        long total) {}
