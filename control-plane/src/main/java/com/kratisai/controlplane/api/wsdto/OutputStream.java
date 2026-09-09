package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** stdout/stderr stream tag for env.output. */
public enum OutputStream {
    STDOUT("stdout"),
    STDERR("stderr");

    private final String value;

    OutputStream(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static OutputStream fromString(String val) {
        for (OutputStream stream : values()) {
            if (stream.value.equals(val) || stream.name().equalsIgnoreCase(val)) {
                return stream;
            }
        }
        throw new IllegalArgumentException("stream must be stdout or stderr");
    }
}
