package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

/**
 * A new client-facing payload requires a new enum constant; conformance tests pin each value to
 * exactly one record and one schema.
 */
public enum ClientPayloadType {
    AUTH("auth"),
    PING("ping"),
    SUBSCRIPTION("subscription"),
    CHAT_SUBSCRIPTION("chat_subscription"),
    MESSAGE("message"),
    MESSAGE_CHUNK("message_chunk"),
    TELEMETRY("telemetry"),
    COMPLETE("complete"),
    CHAT_ERROR("chat_error"),
    CANVAS("canvas"),
    INGESTION("ingestion"),
    TEAM_ENTITY_CHANGED("team_entity_changed"),
    USER_ENTITY_CHANGED("user_entity_changed"),
    EXECUTION_HITL_REQUIRED("execution_hitl_required"),
    EXECUTION_HITL_RESOLVED("execution_hitl_resolved"),
    EXECUTION_OUTPUT("execution_output"),
    EXECUTION_COMPLETE("execution_complete"),
    EXECUTION_STATUS_CHANGED("execution_status_changed"),
    EXECUTION_ACTIVITY("execution_activity"),
    EXECUTION_REPLAY_COMPLETE("execution_replay_complete"),
    EXECUTION_ACP_INITIALIZED("execution_acp_initialized");

    private static final Map<String, ClientPayloadType> BY_WIRE = new HashMap<>();

    static {
        for (ClientPayloadType type : values()) {
            BY_WIRE.put(type.wire, type);
        }
    }

    private final String wire;

    ClientPayloadType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static ClientPayloadType fromWire(String wire) {
        ClientPayloadType type = BY_WIRE.get(wire);
        if (type == null) {
            throw new IllegalArgumentException("Unknown client payload type: " + wire);
        }
        return type;
    }
}
