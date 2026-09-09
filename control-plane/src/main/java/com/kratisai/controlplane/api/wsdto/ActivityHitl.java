package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityHitl(
        @JsonProperty("hitlId") String hitlId,
        @JsonProperty("kind") HitlKind kind,
        @JsonProperty("message") String message,
        @JsonProperty("command") String command,
        @JsonProperty("title") String title,
        @JsonProperty("toolKind") String toolKind,
        @JsonProperty("options") List<PermissionOption> options,
        @JsonProperty("diff") ActivityDiff diff,
        @JsonProperty("form") Map<String, Object> form,
        @JsonProperty("response") HitlResponse response,
        @JsonProperty("optionId") String optionId,
        @JsonProperty("content") Map<String, Object> content,
        @JsonProperty("approved") Boolean approved,
        @JsonProperty("cancelled") Boolean cancelled) {}
