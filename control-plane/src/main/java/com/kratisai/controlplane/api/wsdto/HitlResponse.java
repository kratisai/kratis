package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The user's answer to a HITL request (env.hitl_request result). */
public enum HitlResponse {
    APPROVED("approved"),
    ANSWERED("answered"),
    DECLINED("declined"),
    CANCELLED("cancelled");

    private final String value;

    HitlResponse(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static HitlResponse fromString(String val) {
        for (HitlResponse response : values()) {
            if (response.value.equalsIgnoreCase(val) || response.name().equalsIgnoreCase(val)) {
                return response;
            }
        }
        throw new IllegalArgumentException("Unknown HITL response: " + val);
    }
}
