package com.kratisai.controlplane.api.restdto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;

/**
 * Stable, framework-independent page of results. Serializing Spring Data {@code PageImpl} directly
 * is unsupported because its JSON shape is not guaranteed to be stable, so controllers map service
 * {@link Page} results through {@link #from(Page)}.
 */
@Schema(description = "A stable page of results")
public record PageResponseDto<T>(
        @Schema(description = "The elements in the current page")
        List<T> content,

        @Schema(description = "Zero-based index of the current page", example = "0")
        int number,

        @Schema(description = "Requested size of the current page", example = "20")
        int size,

        @Schema(description = "Total number of elements across all pages")
        long totalElements,

        @Schema(description = "Total number of pages") int totalPages) {

    public PageResponseDto {
        content = List.copyOf(Objects.requireNonNull(content, "content is required"));
    }

    public static <T> PageResponseDto<T> from(Page<T> page) {
        Objects.requireNonNull(page, "page is required");
        return new PageResponseDto<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
