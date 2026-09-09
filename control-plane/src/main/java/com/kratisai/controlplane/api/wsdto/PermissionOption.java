package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** One ACP permission option presented to the user. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionOption(
        @JsonProperty("optionId") String optionId,
        @JsonProperty("name") String name,
        @JsonProperty("kind") ApprovalOptionKind kind) {
    public PermissionOption {
        Objects.requireNonNull(optionId, "optionId is required");
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(kind, "kind is required");
    }
}
