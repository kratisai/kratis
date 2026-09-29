import { vi } from 'vitest'

import { useWebSocketStore } from '@/store/websocket-store'

// ---------------------------------------------------------------------------
// Mock WebSocket class
// ---------------------------------------------------------------------------

export const allInstances: MockWebSocket[] = []

export class MockWebSocket {
  static CLOSED = 3
  static CLOSING = 2
  static CONNECTING = 0
  static OPEN = 1

  close = vi.fn()
  onclose: ((event?: CloseEvent) => void) | null = null
  onerror: ((event: unknown) => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onopen: (() => void) | null = null
  readyState = MockWebSocket.OPEN
  send = vi.fn()
  url: string

  constructor(url: string) {
    if (
      (globalThis as typeof globalThis & { mockWebSocketShouldThrow?: boolean })
        .mockWebSocketShouldThrow
    ) {
      throw new Error('Websocket creation failed')
    }
    this.url = url
    allInstances.push(this)
  }
}

export function getLatestInstance(): MockWebSocket | undefined {
  return allInstances[allInstances.length - 1]
}

// ---------------------------------------------------------------------------
// Connection helpers
// ---------------------------------------------------------------------------

/**
 * Opens a WebSocket connection and simulates a successful auth handshake.
 * Returns the active MockWebSocket instance so tests can push further messages.
 */
export function setupConnected(userId = 'user-1'): MockWebSocket {
  const { connect } = useWebSocketStore.getState()
  connect()
  const ws = getLatestInstance()
  if (!ws) {
    throw new Error('WebSocket instance not created')
  }
  ws.onopen?.()
  ws.onmessage?.({
    data: JSON.stringify({
      id: 0,
      jsonrpc: '2.0',
      result: { status: 'authenticated', type: 'auth', userId },
    }),
  })
  ws.send.mockClear()
  return ws
}

// ---------------------------------------------------------------------------
// WebSocket message trigger helpers
// These emit pre-shaped JSON-RPC frames so tests don't repeat payload boilerplate.
// ---------------------------------------------------------------------------

/** Emit a `complete` (session-load done / chat-turn done) frame. */
export function triggerMockComplete(
  ws: MockWebSocket,
  chatId: string,
  messageId?: string,
  messageCount?: number,
) {
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        chatId,
        messageCount,
        messageId,
        type: 'complete',
      },
    }),
  })
}

/** Emit an `execution_activity` frame from a sandbox agent. */
export function triggerMockExecutionActivity(
  ws: MockWebSocket,
  executionId: string,
  activityType: string,
  description: string,
  status: 'completed' | 'failed' | 'in_progress' | 'pending' = 'in_progress',
  actionId?: string,
  detail?: Record<string, unknown>,
) {
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        actionId,
        activityType,
        description,
        detail,
        executionId,
        status,
        type: 'execution_activity',
      },
    }),
  })
}

/** Emit an `execution_complete` frame from a sandbox task. */
export function triggerMockExecutionComplete(
  ws: MockWebSocket,
  exitCode = 0,
  status = 'SUCCESS',
  executionId = 'exec-1',
) {
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        executionId,
        exitCode,
        status,
        type: 'execution_complete',
      },
    }),
  })
}

/** Emit an `execution_output` (stdout/stderr) frame from a sandbox task. */
export function triggerMockExecutionOutput(
  ws: MockWebSocket,
  line: string,
  stream: 'stderr' | 'stdout' = 'stdout',
  executionId = 'exec-1',
) {
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        executionId,
        line,
        stream,
        type: 'execution_output',
      },
    }),
  })
}

/** Emit a chat `message` frame (echoed user message or assistant reply). */
export function triggerMockMessageEcho(
  ws: MockWebSocket,
  content: string,
  chatId: string,
  role: 'assistant' | 'user' = 'user',
  messageId = 'msg-1',
) {
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        chatId,
        content,
        messageId,
        role,
        timestamp: new Date().toISOString(),
        type: 'message',
      },
    }),
  })
}

/** Emit an `execution_hitl_required` frame (kind=approval). */
export function triggerMockPermissionRequired(
  ws: MockWebSocket,
  executionId: string,
  command: string,
  options: Array<{ kind: string; name: string; optionId: string }> = [
    { kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' },
    { kind: 'reject_once', name: 'Reject', optionId: 'reject-once' },
  ],
  hitlId = command,
) {
  triggerMockExecutionActivity(ws, executionId, 'COMMAND', command, 'pending', hitlId, {
    hitl: { hitlId, kind: 'approval', message: `Allow ${command}?`, options },
  })
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        command,
        executionId,
        hitlId,
        kind: 'approval',
        message: `Allow ${command}?`,
        options,
        type: 'execution_hitl_required',
      },
    }),
  })
}

/** Emit an `execution_hitl_resolved` frame (kind=approval). */
export function triggerMockPermissionResolved(
  ws: MockWebSocket,
  executionId: string,
  command: string,
  approved: boolean,
  resolvedByUserId: null | string,
  resolvedByDisplayName: string,
  hitlId = command,
  optionId = approved ? 'allow-once' : 'reject-once',
  response: 'approved' | 'cancelled' | 'declined' = approved ? 'approved' : 'declined',
) {
  ws.onmessage?.({
    data: JSON.stringify({
      jsonrpc: '2.0',
      result: {
        command,
        executionId,
        hitlId,
        kind: 'approval',
        optionId: response === 'cancelled' ? null : optionId,
        resolvedByDisplayName,
        resolvedByUserId,
        response,
        type: 'execution_hitl_resolved',
      },
    }),
  })
}

/**
 * Emit a `telemetry` frame.
 * - Pass only `text` for a thought update.
 * - Pass `taskId` + `toolName` for a tool-start event.
 */
export function triggerMockTelemetry(
  ws: MockWebSocket,
  text: string,
  taskId?: string,
  toolName?: string,
) {
  const event = taskId ? { taskId, thought: text, toolName } : { text }
  ws.onmessage?.({
    data: JSON.stringify({
      id: 1,
      jsonrpc: '2.0',
      result: {
        event,
        type: 'telemetry',
      },
    }),
  })
}
