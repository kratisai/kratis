package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** One task in the ACP execution plan. The plan is replace-all per update. */
public record PlanEntry(
        @JsonProperty("content") String content,
        @JsonProperty("priority") PlanEntryPriority priority,
        @JsonProperty("status") PlanEntryStatus status) {

    public PlanEntry {
        Objects.requireNonNull(content, "content is required");
        Objects.requireNonNull(priority, "priority is required");
        Objects.requireNonNull(status, "status is required");
    }
}
