package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire values for {@code env.sidecar_error} kind. */
public enum SidecarErrorKind {
    CONNECTION_CLOSED("connection_closed"),
    FRAME_DROPPED("frame_dropped");

    private final String value;

    SidecarErrorKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static SidecarErrorKind fromString(String val) {
        for (SidecarErrorKind kind : values()) {
            if (kind.value.equals(val) || kind.name().equalsIgnoreCase(val)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown sidecar error kind: " + val);
    }
}
