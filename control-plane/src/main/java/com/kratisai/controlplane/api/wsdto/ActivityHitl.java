package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlRequiredResult;
import com.kratisai.controlplane.api.wsdto.ClientPayload.ExecutionHitlResolvedResult;
import java.util.List;
import java.util.Map;

/** Persisted HITL history. Tolerates unknown keys so older stored rows still replay. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityHitl(
        @JsonProperty("hitlId") String hitlId,
        @JsonProperty("kind") HitlKind kind,
        @JsonProperty("message") String message,
        @JsonProperty("command") String command,
        @JsonProperty("commandSegments") List<CommandSegment> commandSegments,
        @JsonProperty("title") String title,
        @JsonProperty("toolKind") String toolKind,
        @JsonProperty("options") List<PermissionOption> options,
        @JsonProperty("diff") ActivityDiff diff,
        @JsonProperty("form") Map<String, Object> form,
        @JsonProperty("response") HitlResponse response,
        @JsonProperty("optionId") String optionId,
        @JsonProperty("content") Map<String, Object> content) {

    public static ActivityHitl from(ExecutionHitlRequiredResult request) {
        return new ActivityHitl(
                request.hitlId(),
                request.kind(),
                request.message(),
                request.command(),
                request.commandSegments(),
                request.title(),
                request.toolKind(),
                request.options(),
                request.diff(),
                request.form(),
                null,
                null,
                null);
    }

    /** Minimal history row when the request snapshot was never persisted. */
    public static ActivityHitl from(ExecutionHitlResolvedResult resolution) {
        return new ActivityHitl(
                resolution.hitlId(),
                resolution.kind(),
                resolution.hitlId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resolution.response(),
                resolution.optionId(),
                resolution.content());
    }

    public ActivityHitl withResolution(ExecutionHitlResolvedResult resolution) {
        return new ActivityHitl(
                hitlId,
                kind != null ? kind : resolution.kind(),
                message != null ? message : resolution.hitlId(),
                command,
                commandSegments,
                title,
                toolKind,
                options,
                diff,
                form,
                resolution.response(),
                resolution.optionId(),
                resolution.content());
    }
}
