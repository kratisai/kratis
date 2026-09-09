package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Result status for env.checkout_complete. */
public enum CheckoutStatus {
    SUCCESS("success"),
    FAILED("failed");

    private final String value;

    CheckoutStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static CheckoutStatus fromString(String val) {
        for (CheckoutStatus status : values()) {
            if (status.value.equals(val) || status.name().equalsIgnoreCase(val)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown checkout status: " + val);
    }
}
