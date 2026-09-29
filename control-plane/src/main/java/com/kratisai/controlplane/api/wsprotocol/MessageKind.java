package com.kratisai.controlplane.api.wsprotocol;

/**
 * Whether a JSON-RPC method expects a response.
 *
 * <p>Requests carry an {@code id} and expect a response; notifications do not.
 *
 * <p>The wire values mirror {@code x-kratis-message-kind} in the OpenRPC protocol files.
 */
public enum MessageKind {
    REQUEST("request"),
    NOTIFICATION("notification");

    private final String wire;

    MessageKind(String wire) {
        this.wire = wire;
    }

    public static MessageKind fromWire(String value) {
        for (MessageKind kind : values()) {
            if (kind.wire.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown message kind: " + value);
    }
}
