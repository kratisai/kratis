package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

/** Attachment kinds supported by env.attachment. Images and markdown only initially. */
public enum AttachmentKind {
    IMAGE("image"),
    MARKDOWN("markdown");

    private static final Map<String, AttachmentKind> BY_WIRE = new HashMap<>();

    static {
        for (AttachmentKind kind : values()) {
            BY_WIRE.put(kind.wire, kind);
        }
    }

    private final String wire;

    AttachmentKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getValue() {
        return wire;
    }

    @JsonCreator
    public static AttachmentKind fromWire(String wire) {
        AttachmentKind kind = BY_WIRE.get(wire);
        if (kind == null) {
            throw new IllegalArgumentException("Unknown attachment kind: " + wire);
        }
        return kind;
    }
}
