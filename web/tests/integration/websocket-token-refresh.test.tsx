import { act, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import {
  allInstances,
  getLatestInstance,
  type MockWebSocket,
  setupConnected,
} from '../support/test-websocket'

const USER = { email: 'test@test.com', id: 'user-1', name: 'Test User' }

function authResult(userId = 'user-1') {
  return {
    data: JSON.stringify({
      id: 1,
      jsonrpc: '2.0',
      result: { status: 'authenticated', type: 'auth', userId },
    }),
  }
}

function sentMethods(
  ws: MockWebSocket,
): Array<{ method: string; params: Record<string, unknown> }> {
  return ws.send.mock.calls.map(([payload]) => {
    const parsed = JSON.parse(payload as string) as {
      method: string
      params: Record<string, unknown>
    }
    return { method: parsed.method, params: parsed.params }
  })
}

function sentRequests(ws: MockWebSocket): Array<{ id: number; method: string }> {
  return ws.send.mock.calls.map(([payload]) => {
    const parsed = JSON.parse(payload as string) as { id: number; method: string }
    return { id: parsed.id, method: parsed.method }
  })
}

describe('WebSocket Token Refresh', () => {
  setupFetchMock()

  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.getState().logout()
    useWebSocketStore.getState().disconnect()
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
    vi.useRealTimers()
  })

  it('keeps the same WebSocket open and re-authenticates in place when tokens refresh', () => {
    useAuthStore.getState().login(USER, 'token-1', 'refresh-1', 3600)
    const ws = setupConnected()
    const instanceCount = allInstances.length
    ws.send.mockClear()

    act(() => {
      useAuthStore.getState().updateTokens('token-2', 'refresh-2', 3600)
    })

    // The socket must not be torn down or recreated on token refresh
    expect(allInstances.length).toBe(instanceCount)
    expect(ws.close).not.toHaveBeenCalled()
    expect(useWebSocketStore.getState().isConnected).toBe(true)

    // A fresh auth handshake with the new token is sent over the SAME socket
    expect(sentMethods(ws)).toEqual([{ method: 'auth', params: { token: 'token-2' } }])
  })

  it('re-subscribes after an in-place re-auth is confirmed', async () => {
    useAuthStore.getState().login(USER, 'token-1', 'refresh-1', 3600)
    useAuthStore.getState().setCurrentTeamId('team-1')

    const { connect } = useWebSocketStore.getState()
    connect()
    const ws = getLatestInstance()
    if (!ws) throw new Error('WebSocket instance not created')

    act(() => {
      ws.onopen?.()
      ws.onmessage?.(authResult())
    })

    // The initial session subscribes only once the server confirms the session.
    await waitFor(() => {
      expect(sentMethods(ws).some((m) => m.method === 'subscribe')).toBe(true)
    })
    ws.send.mockClear()

    act(() => {
      useAuthStore.getState().updateTokens('token-2', 'refresh-2', 3600)
    })

    // The socket is re-authenticated in place with the new token...
    expect(sentMethods(ws)).toEqual([{ method: 'auth', params: { token: 'token-2' } }])

    // ...and the confirmation re-establishes the team subscription.
    act(() => {
      ws.onmessage?.(authResult())
    })

    await waitFor(() => {
      expect(sentMethods(ws).some((m) => m.method === 'subscribe')).toBe(true)
    })
  })

  it('retries a rejected subscription', async () => {
    useAuthStore.getState().login(USER, 'token-1', 'refresh-1', 3600)
    useAuthStore.getState().setCurrentTeamId('team-1')

    const { connect } = useWebSocketStore.getState()
    connect()
    const ws = getLatestInstance()
    if (!ws) throw new Error('WebSocket instance not created')

    act(() => {
      ws.onopen?.()
      ws.onmessage?.(authResult())
    })

    await waitFor(() => {
      expect(sentRequests(ws).some((r) => r.method === 'subscribe')).toBe(true)
    })
    const initialSubscribes = sentRequests(ws).filter((r) => r.method === 'subscribe')
    const rejected = initialSubscribes[initialSubscribes.length - 1]

    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          error: { code: -32000, message: 'Not authenticated' },
          id: rejected.id,
          jsonrpc: '2.0',
        }),
      })
    })

    await waitFor(
      () => {
        const subscribes = sentRequests(ws).filter((r) => r.method === 'subscribe')
        expect(subscribes.length).toBeGreaterThan(initialSubscribes.length)
      },
      { timeout: 3000 },
    )
  })

  it('re-authenticates a connection that was opened with an expired token', () => {
    useAuthStore.getState().login(USER, 'expired-token', 'refresh-1', -1)
    const ws = setupConnected()
    const instanceCount = allInstances.length
    ws.send.mockClear()

    act(() => {
      useAuthStore.getState().updateTokens('fresh-token', 'refresh-2', 3600)
    })

    // The pending connection is re-authenticated instead of waiting for a reconnect
    expect(allInstances.length).toBe(instanceCount)
    expect(ws.close).not.toHaveBeenCalled()
    expect(sentMethods(ws)).toEqual([{ method: 'auth', params: { token: 'fresh-token' } }])
  })

  it('does not re-authenticate when the access token is unchanged', () => {
    useAuthStore.getState().login(USER, 'token-1', 'refresh-1', 3600)
    const ws = setupConnected()
    ws.send.mockClear()

    // Trigger an unrelated auth-store change that keeps the same token
    act(() => {
      useAuthStore.getState().addSession({
        createdAt: new Date(),
        id: 'session-1',
        title: 'Test Session',
        type: 'agent',
      })
    })

    expect(sentMethods(ws)).toEqual([])
  })

  it('sends heartbeat pings while connected and stops after close', () => {
    vi.useFakeTimers()
    useAuthStore.getState().login(USER, 'token-1', 'refresh-1', 3600)
    const ws = setupConnected()
    ws.send.mockClear()

    act(() => {
      vi.advanceTimersByTime(5 * 60_000)
    })

    const pings = sentMethods(ws).filter((m) => m.method === 'ping')
    expect(pings.length).toBeGreaterThan(0)
    expect(pings[0]).toEqual({ method: 'ping', params: {} })

    // After the socket closes the heartbeat must stop
    ws.send.mockClear()
    act(() => {
      ws.onclose?.({} as CloseEvent)
      vi.advanceTimersByTime(120_000)
    })

    expect(sentMethods(ws).filter((m) => m.method === 'ping')).toEqual([])
  })

  it('forces a reconnect when a heartbeat ping is not answered', () => {
    vi.useFakeTimers()
    useAuthStore.getState().login(USER, 'token-1', 'refresh-1', 3600)
    const ws = setupConnected()
    const instanceCount = allInstances.length

    // Fire the heartbeat and let the pong timeout elapse with no inbound frame.
    act(() => {
      vi.advanceTimersByTime(5 * 60_000)
    })
    act(() => {
      vi.advanceTimersByTime(30_000)
    })

    expect(ws.close).toHaveBeenCalled()

    // Simulate the browser dispatching the close event, then let the reconnect fire.
    act(() => {
      ws.onclose?.({} as CloseEvent)
    })
    act(() => {
      vi.advanceTimersByTime(5000)
    })

    expect(allInstances.length).toBeGreaterThan(instanceCount)
  })
})
