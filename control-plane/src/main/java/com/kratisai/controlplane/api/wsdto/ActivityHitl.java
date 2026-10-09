package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Persisted HITL history. Tolerates unknown keys so older stored rows still replay. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityHitl(
        @JsonProperty("kind") HitlKind kind,
        @JsonProperty("state") HitlState state,
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
        @JsonProperty("content") Map<String, Object> content,
        @JsonProperty("resolvedBy") HitlResolver resolvedBy) {

    public static ActivityHitl from(HitlRequestSnapshot request, HitlState state) {
        return new ActivityHitl(
                request.kind(),
                state,
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
                null,
                null);
    }

    /** Minimal history row when the request snapshot was never persisted. */
    public static ActivityHitl from(HitlResolution resolution) {
        return new ActivityHitl(
                resolution.kind(),
                HitlState.RESOLVED,
                resolution.actionId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resolution.response(),
                resolution.optionId(),
                resolution.content(),
                resolution.resolvedBy());
    }

    public ActivityHitl withResolution(HitlResolution resolution) {
        return new ActivityHitl(
                kind != null ? kind : resolution.kind(),
                HitlState.RESOLVED,
                message != null ? message : resolution.actionId(),
                command,
                commandSegments,
                title,
                toolKind,
                options,
                diff,
                form,
                resolution.response(),
                resolution.optionId(),
                resolution.content(),
                resolution.resolvedBy());
    }
}
