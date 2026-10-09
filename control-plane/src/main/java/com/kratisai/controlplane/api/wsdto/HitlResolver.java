package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/** Who or what resolved a HITL request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HitlResolver(
        @JsonProperty("kind") HitlResolverKind kind,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("displayName") String displayName) {

    public HitlResolver {
        Objects.requireNonNull(kind, "kind is required");
    }

    public static HitlResolver user(UUID userId, String displayName) {
        return new HitlResolver(
                HitlResolverKind.USER, Objects.requireNonNull(userId, "userId is required"), displayName);
    }

    public static HitlResolver rule(String displayName) {
        return new HitlResolver(HitlResolverKind.RULE, null, displayName);
    }

    public static HitlResolver timeout() {
        return new HitlResolver(HitlResolverKind.TIMEOUT, null, null);
    }

    public static HitlResolver system() {
        return new HitlResolver(HitlResolverKind.SYSTEM, null, null);
    }
}
