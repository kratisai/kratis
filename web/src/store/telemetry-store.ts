import type {
  TelemetryEvent,
  ThoughtEvent,
  ToolCompleteEvent,
  ToolErrorEvent,
  ToolStartEvent,
} from '@/types/websocket-types'

export function isThoughtEvent(event: TelemetryEvent): event is ThoughtEvent {
  return 'text' in event && !('taskId' in event) && !('toolName' in event)
}

export function isToolCompleteEvent(event: TelemetryEvent): event is ToolCompleteEvent {
  return 'status' in event && 'taskId' in event && !('toolName' in event) && !('error' in event)
}

export function isToolErrorEvent(event: TelemetryEvent): event is ToolErrorEvent {
  return 'error' in event && 'taskId' in event
}

export function isToolStartEvent(event: TelemetryEvent): event is ToolStartEvent {
  return 'toolName' in event && 'taskId' in event
}
