package com.kratisai.controlplane.planningagent.telemetry;

public sealed interface TelemetryEvent {

    record Thought(String text) implements TelemetryEvent {}

    record ToolStart(String taskId, String toolName, String thought) implements TelemetryEvent {}

    record ToolUpdate(String taskId, String status) implements TelemetryEvent {}

    record ToolComplete(String taskId, String status) implements TelemetryEvent {
        public ToolComplete {
            if (status == null) {
                status = "success";
            }
        }
    }

    record ToolError(String taskId, String error) implements TelemetryEvent {}
}
