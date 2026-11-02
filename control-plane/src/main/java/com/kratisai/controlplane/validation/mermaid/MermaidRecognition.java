package com.kratisai.controlplane.validation.mermaid;

public record MermaidRecognition(
        boolean ok, String type, String source, String renderText, String rule, String error) {}
