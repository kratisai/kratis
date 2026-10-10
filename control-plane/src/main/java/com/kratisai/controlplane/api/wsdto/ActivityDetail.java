package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityDetail(
        @JsonProperty("kind") ActivityKind kind,
        @JsonProperty("title") String title,
        @JsonProperty("locations") List<ActivityLocation> locations,
        @JsonProperty("output") String output,
        @JsonProperty("diff") ActivityDiff diff,
        @JsonProperty("exitCode") Integer exitCode,
        @JsonProperty("truncated") Boolean truncated,
        @JsonProperty("messageId") String messageId,
        @JsonProperty("role") String role,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("plan")
        List<PlanEntry> plan,

        @JsonProperty("hitl") ActivityHitl hitl) {

    public ActivityDetail withHitl(ActivityHitl hitl) {
        return new ActivityDetail(
                kind, title, locations, output, diff, exitCode, truncated, messageId, role, plan, hitl);
    }
}
