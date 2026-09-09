package com.kratisai.controlplane.api.wsdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The before/after text of an edit tool. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityDiff(
        @JsonProperty("oldText") String oldText,
        @JsonProperty("newText") String newText,
        @JsonProperty("path") String path) {}
