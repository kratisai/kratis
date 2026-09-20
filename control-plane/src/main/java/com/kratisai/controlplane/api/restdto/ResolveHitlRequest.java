package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.api.wsdto.HitlResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResolveHitlRequest(
        @NotNull UUID executionId,
        @NotBlank String hitlId,
        @NotNull HitlResponse response,
        String optionId,
        Map<String, Object> content,
        @Valid List<CreateHitlRuleRequest> rules) {

    private static final int MAX_RULES = 25;

    public ResolveHitlRequest {
        if (rules != null && rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("Too many rules (max " + MAX_RULES + ")");
        }
    }

    public ResolveHitlRequest(
            UUID executionId, String hitlId, HitlResponse response, String optionId, Map<String, Object> content) {
        this(executionId, hitlId, response, optionId, content, null);
    }
}
