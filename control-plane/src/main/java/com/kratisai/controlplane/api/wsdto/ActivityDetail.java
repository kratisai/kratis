package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityDetail(
        @JsonProperty("kind") ActivityKind kind,
        @JsonProperty("title") String title,
        @JsonProperty("locations") List<ActivityLocation> locations,
        @JsonProperty("input") Map<String, Object> input,
        @JsonProperty("output") String output,
        @JsonProperty("diff") ActivityDiff diff,
        @JsonProperty("exitCode") Integer exitCode,
        @JsonProperty("truncated") Boolean truncated,
        @JsonProperty("meta") Map<String, Object> meta,
        @JsonProperty("messageId") String messageId,
        @JsonProperty("role") String role,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("plan")
        List<PlanEntry> plan,

        @JsonProperty("rawUpdate") Map<String, Object> rawUpdate,
        @JsonProperty("hitl") ActivityHitl hitl) {

    public ActivityDetail withHitl(ActivityHitl hitl) {
        return new ActivityDetail(
                kind, title, locations, input, output, diff, exitCode, truncated, meta, messageId, role, plan,
                rawUpdate, hitl);
    }
}
