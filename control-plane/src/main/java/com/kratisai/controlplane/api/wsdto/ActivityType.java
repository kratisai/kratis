package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire values for env.activity activityType. */
public enum ActivityType {
    THINKING("THINKING"),
    RESEARCH("RESEARCH"),
    EDITED("EDITED"),
    COMMAND("COMMAND"),
    MESSAGE("MESSAGE"),
    ELICITATION("ELICITATION"),
    PLAN("PLAN"),
    ERROR("ERROR");

    private final String value;

    ActivityType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static ActivityType fromString(String val) {
        for (ActivityType type : values()) {
            if (type.value.equalsIgnoreCase(val) || type.name().equalsIgnoreCase(val)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown activity type: " + val);
    }
}
