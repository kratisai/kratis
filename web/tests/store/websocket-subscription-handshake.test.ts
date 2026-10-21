import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useActivityStore } from '@/store/activity-store'
import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { getLatestInstance, MockWebSocket } from '../support/test-websocket'

function authFrame(userId = 'user-1') {
  return {
    data: JSON.stringify({
      id: 1,
      jsonrpc: '2.0',
      result: { status: 'authenticated', type: 'auth', userId },
    }),
  }
}

function sentMethods(ws: MockWebSocket): string[] {
  return ws.send.mock.calls.map(([frame]) => (JSON.parse(frame as string).method as string))
}

describe('websocket-store subscription handshake', () => {
  setupFetchMock()

  beforeEach(() => {
    vi.useFakeTimers()
    useChatStore.getState().clearChats()
    useExecutionStore.setState({ subscriberCounts: {} })
    useAuthStore
      .getState()
      .login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600,
      )
    useAuthStore.getState().setCurrentTeamId('team-1')
  })

  afterEach(() => {
    useExecutionStore.setState({ subscriberCounts: {} })
    vi.useRealTimers()
  })

  it('does not emit subscriptions before the server confirms auth', () => {
    useWebSocketStore.getState().connect()
    const ws = getLatestInstance()
    if (!ws) throw new Error('WebSocket instance not created')
    ws.onopen?.()

    expect(sentMethods(ws)).toEqual(['auth'])

    useChatStore.getState().subscribeChat('chat-1')
    useExecutionStore.getState().subscribeExecution('exec-1')

    expect(sentMethods(ws)).not.toContain('chat.subscribe')
    expect(sentMethods(ws)).not.toContain('execution.subscribe')
  })

  it('sends exactly one chat.subscribe and execution.subscribe once auth completes', async () => {
    useWebSocketStore.getState().connect()
    const ws = getLatestInstance()
    if (!ws) throw new Error('WebSocket instance not created')
    ws.onopen?.()

    useChatStore.getState().subscribeChat('chat-1')
    useExecutionStore.getState().subscribeExecution('exec-1')
    ws.send.mockClear()

    ws.onmessage?.(authFrame())
    await vi.advanceTimersByTimeAsync(20)

    const methods = sentMethods(ws)
    expect(methods.filter((method) => method === 'chat.subscribe')).toHaveLength(1)
    expect(methods.filter((method) => method === 'execution.subscribe')).toHaveLength(1)
  })

  it('flushes a queued non-subscription request only after auth', async () => {
    useWebSocketStore.getState().connect()
    const ws = getLatestInstance()
    if (!ws) throw new Error('WebSocket instance not created')
    ws.onopen?.()

    useExecutionStore.getState().requestLogs('exec-1')
    expect(sentMethods(ws)).not.toContain('execution.get_logs')

    ws.send.mockClear()
    ws.onmessage?.(authFrame())
    await vi.advanceTimersByTimeAsync(20)

    expect(sentMethods(ws)).toContain('execution.get_logs')
  })

  it('rebuilds the activity log and re-subscribes on reconnect', async () => {
    useWebSocketStore.getState().connect()
    const first = getLatestInstance()
    if (!first) throw new Error('WebSocket instance not created')
    first.onopen?.()
    first.onmessage?.(authFrame())
    await vi.advanceTimersByTimeAsync(20)

    useExecutionStore.getState().subscribeExecution('exec-1')
    const clearSpy = vi.spyOn(useActivityStore.getState(), 'clearActivities')

    useWebSocketStore.getState().disconnect()
    useWebSocketStore.getState().connect()
    const second = getLatestInstance()
    if (!second || second === first) throw new Error('reconnect did not open a new socket')
    second.onopen?.()
    second.onmessage?.(authFrame())
    await vi.advanceTimersByTimeAsync(20)

    expect(clearSpy).toHaveBeenCalledWith('exec-1')
    expect(sentMethods(second)).toContain('execution.subscribe')
    clearSpy.mockRestore()
  })
})
