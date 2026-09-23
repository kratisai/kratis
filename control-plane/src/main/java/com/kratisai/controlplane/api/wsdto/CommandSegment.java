package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kratisai.controlplane.model.HitlRuleType;
import java.util.Objects;

/** One approvable unit: a shell command root, or a tool kind for non-command requests. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandSegment(
        @JsonProperty("text") String text,
        @JsonProperty("suggestedRoot") String suggestedRoot,
        @JsonProperty("ruleType") HitlRuleType ruleType,
        @JsonProperty("preApproved") Boolean preApproved) {
    public CommandSegment {
        Objects.requireNonNull(text, "text is required");
        Objects.requireNonNull(suggestedRoot, "suggestedRoot is required");
    }

    public CommandSegment(String text, String suggestedRoot, HitlRuleType ruleType) {
        this(text, suggestedRoot, ruleType, null);
    }
}
