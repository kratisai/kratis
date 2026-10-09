package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persisted HITL history. Tolerates unknown keys so older stored rows still replay. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityHitl(
        @JsonProperty("hitlId") String hitlId,
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
        @JsonProperty("resolvedBy") String resolvedBy,
        @JsonProperty("resolvedByUserId") UUID resolvedByUserId) {

    public static ActivityHitl from(HitlRequestSnapshot request, HitlState state) {
        return new ActivityHitl(
                request.hitlId(),
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
                null,
                null);
    }

    /** Minimal history row when the request snapshot was never persisted. */
    public static ActivityHitl from(HitlResolution resolution) {
        return new ActivityHitl(
                resolution.hitlId(),
                resolution.kind(),
                HitlState.RESOLVED,
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
                resolution.content(),
                resolution.resolvedByDisplayName(),
                resolution.resolvedByUserId());
    }

    public ActivityHitl withResolution(HitlResolution resolution) {
        return new ActivityHitl(
                hitlId,
                kind != null ? kind : resolution.kind(),
                HitlState.RESOLVED,
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
                resolution.content(),
                resolution.resolvedByDisplayName() != null ? resolution.resolvedByDisplayName() : resolvedBy,
                resolution.resolvedByUserId() != null ? resolution.resolvedByUserId() : resolvedByUserId);
    }
}
