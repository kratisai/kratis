package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

public enum EnvironmentResultType {
    ENV_REGISTER("env_register"),
    ENV_HEARTBEAT("env_heartbeat");

    private static final Map<String, EnvironmentResultType> BY_WIRE = new HashMap<>();

    static {
        for (EnvironmentResultType type : values()) {
            BY_WIRE.put(type.wire, type);
        }
    }

    private final String wire;

    EnvironmentResultType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static EnvironmentResultType fromWire(String wire) {
        EnvironmentResultType type = BY_WIRE.get(wire);
        if (type == null) {
            throw new IllegalArgumentException("Unknown environment result type: " + wire);
        }
        return type;
    }
}
