package com.kratisai.controlplane.api.wsdto;

import java.util.Locale;
import java.util.Optional;

/** Closed ACP tool-kind set plus WRITE, the sidecar alias for fs/write_text_file. */
public enum ToolKind {
    READ,
    EDIT,
    WRITE,
    DELETE,
    MOVE,
    SEARCH,
    EXECUTE,
    THINK,
    FETCH,
    SWITCH_MODE,
    OTHER;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ToolKind> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ToolKind kind : values()) {
            if (kind.wireValue().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public static String effectiveWireValue(String toolKind) {
        if (toolKind == null || toolKind.isBlank()) {
            return EXECUTE.wireValue();
        }
        return toolKind.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isCommandLike(String toolKind) {
        return EXECUTE.wireValue().equals(effectiveWireValue(toolKind));
    }
}
