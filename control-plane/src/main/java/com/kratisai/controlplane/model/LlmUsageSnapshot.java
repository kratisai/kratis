package com.kratisai.controlplane.model;

public record LlmUsageSnapshot(Double spend, Long totalTokens, Long promptTokens, Long completionTokens) {}
