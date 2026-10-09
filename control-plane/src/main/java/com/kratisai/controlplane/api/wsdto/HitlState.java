package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum HitlState {
    @JsonProperty("awaiting_human")
    AWAITING_HUMAN,

    @JsonProperty("resolved")
    RESOLVED
}
