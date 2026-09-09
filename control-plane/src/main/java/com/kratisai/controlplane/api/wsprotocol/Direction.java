package com.kratisai.controlplane.api.wsprotocol;

/**
 * Which component sends a JSON-RPC method.
 *
 * <p>The wire values mirror {@code x-kratis-direction} in the OpenRPC protocol files.
 */
public enum Direction {
    WEB_TO_CONTROL_PLANE("web-to-control-plane"),
    CONNECTOR_TO_CONTROL_PLANE("connector-to-control-plane"),
    CONTROL_PLANE_TO_CONNECTOR("control-plane-to-connector");

    private final String wire;

    Direction(String wire) {
        this.wire = wire;
    }

    /**
     * Returns the direction matching the wire value declared in the OpenRPC protocol.
     *
     * @param value the {@code x-kratis-direction} value
     * @return the matching direction
     * @throws IllegalArgumentException when the value is not a known direction
     */
    public static Direction fromWire(String value) {
        for (Direction direction : values()) {
            if (direction.wire.equals(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown direction: " + value);
    }
}
