package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One file location referenced by a tool call. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityLocation(
        @JsonProperty("path") String path,
        @JsonProperty("line") Integer line) {}
