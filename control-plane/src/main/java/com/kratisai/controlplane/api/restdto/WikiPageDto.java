package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Wiki page information")
public record WikiPageDto(
        @Schema(description = "Page ID") UUID id,
        @Schema(description = "Repository name") String repoName,

        @Schema(description = "Parent page ID, null for top-level pages", nullable = true)
        UUID parentPageId,

        @Schema(description = "URL-friendly page slug") String pageSlug,
        @Schema(description = "Page title") String title,
        @Schema(description = "Display order index") Integer orderIndex,
        @Schema(description = "Markdown content") String content,

        @Schema(description = "Whether this page has child pages")
        boolean hasChildren) {}
