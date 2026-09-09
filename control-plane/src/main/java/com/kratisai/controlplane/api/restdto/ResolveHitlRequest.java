package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.api.wsdto.HitlResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record ResolveHitlRequest(
        @NotNull UUID executionId,
        @NotBlank String hitlId,
        @NotNull HitlResponse response,
        String optionId,
        Map<String, Object> content) {}
