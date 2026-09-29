package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Optional;

/**
 * Closed ACP tool-kind set plus WRITE, the sidecar alias for fs/write_text_file.
 * Backs both activity detail kinds and HITL permission/rule matching, so the
 * vocabulary is defined exactly once.
 */
public enum ActivityKind {
    READ("read"),
    EDIT("edit"),
    WRITE("write"),
    DELETE("delete"),
    MOVE("move"),
    SEARCH("search"),
    EXECUTE("execute"),
    THINK("think"),
    FETCH("fetch"),
    SWITCH_MODE("switch_mode"),
    OTHER("other");

    private final String value;

    ActivityKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Lenient wire-value lookup: empty when the value is absent or unknown. */
    public static Optional<ActivityKind> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        for (ActivityKind kind : values()) {
            if (kind.value.equalsIgnoreCase(normalized) || kind.name().equalsIgnoreCase(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    @JsonCreator
    public static ActivityKind fromString(String val) {
        return fromWireValue(val).orElseThrow(() -> new IllegalArgumentException("Unknown activity kind: " + val));
    }

    /** Wire value for a possibly-absent tool kind, defaulting to EXECUTE. */
    public static String effectiveWireValue(String toolKind) {
        if (toolKind == null || toolKind.isBlank()) {
            return EXECUTE.value;
        }
        return toolKind.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isCommandLike(String toolKind) {
        return EXECUTE.value.equals(effectiveWireValue(toolKind));
    }
}
