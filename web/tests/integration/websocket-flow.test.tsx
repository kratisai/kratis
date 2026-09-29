import { act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { queryClient } from '@/lib/query-client'
import { useAuthStore } from '@/store/auth-store'
import { useExecutionStore } from '@/store/execution-store'
import { useWebSocketStore } from '@/store/websocket-store'

import {
  mockListChatExecutions,
  mockListChats,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import { allInstances, type MockWebSocket, setupConnected } from '../support/test-websocket'

function sentMethodNames(ws: MockWebSocket): string[] {
  return ws.send.mock.calls.map(
    ([payload]) => (JSON.parse(payload as string) as { method: string }).method,
  )
}

describe('WebSocket Integration Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    mockListChats([])
    useExecutionStore.setState({ logs: {} })
    vi.clearAllMocks()
  })

  afterEach(() => {
    delete (globalThis as typeof globalThis & { mockWebSocketShouldThrow?: boolean })
      .mockWebSocketShouldThrow
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
    vi.useRealTimers()
  })

  it('reflects WebSocket connection state in SessionView', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    // Render SessionView
    renderWithRouter(['/chats/test-session'])

    // Initially should show disconnected state in the session header
    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })

    // Setup connected socket
    const ws = setupConnected()

    // Header should now transition to "Connected"
    await waitFor(() => {
      expect(screen.getByText('Connected')).toBeInTheDocument()
    })

    // Trigger close on the socket
    act(() => {
      ws.onclose?.({} as CloseEvent)
    })

    // Header should go back to "Disconnected"
    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })
  })

  it('streams execution output and execution complete messages to the terminal view', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    mockListChatExecutions([
      {
        chatId: 'test-session',
        completedAt: null,
        exitCode: null,
        id: 'exec-1',
        startedAt: '2024-01-01T00:00:00Z',
        status: 'RUNNING',
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    // Open terminal store state directly for rendering logs
    useExecutionStore.setState({ terminalOpen: true })

    // Render SessionView
    renderWithRouter(['/chats/test-session'])

    // Setup socket
    const ws = setupConnected()

    // Stream execution output over WebSocket
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            executionId: 'exec-1',
            line: 'Executing build task...',
            stream: 'stdout',
            type: 'execution_output',
          },
        }),
      })

      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            executionId: 'exec-1',
            line: 'Deprecation warning occurred',
            stream: 'stderr',
            type: 'execution_output',
          },
        }),
      })

      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            executionId: 'exec-1',
            exitCode: 0,
            status: 'SUCCESS',
            type: 'execution_complete',
          },
        }),
      })
    })

    // Verify logs render in JSDOM DOM
    await waitFor(() => {
      expect(screen.getByText('[Output] Executing build task...')).toBeInTheDocument()
      expect(screen.getByText('[Error] Deprecation warning occurred')).toBeInTheDocument()
      expect(
        screen.getByText('[System] Command completed successfully with exit code 0.'),
      ).toBeInTheDocument()
    })
  })

  it('invalidates the chat execution query on execution_status_changed', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const ws = setupConnected()

    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            chatId: 'chat-1',
            executionId: 'exec-1',
            teamId: 'team-1',
            type: 'execution_status_changed',
          },
        }),
      })
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['chat-executions', 'chat-1'],
      })
    })
  })

  it('triggers query cache invalidation on team and user entity change events', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    // Setup socket
    const ws = setupConnected()

    // Trigger team entity change for repositories
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            entity: 'REPOSITORIES',
            teamId: 'team-1',
            type: 'team_entity_changed',
          },
        }),
      })
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['repositories', 'team-1'],
      })
    })

    // Trigger team entity change for environments
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            entity: 'ENVIRONMENTS',
            teamId: 'team-1',
            type: 'team_entity_changed',
          },
        }),
      })
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['environments', 'team-1'],
      })
    })

    // Trigger user entity change for teams
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            entity: 'TEAMS',
            type: 'user_entity_changed',
            userId: 'user-1',
          },
        }),
      })
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['teams'],
      })
    })
  })

  it('displays a warning if WebSocket receives invalid JSON data', async () => {
    const ws = setupConnected()
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    act(() => {
      ws.onmessage?.({ data: 'invalid-json{' })
    })

    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('Failed to parse WebSocket message:'),
      expect.any(Error),
    )
    consoleSpy.mockRestore()
  })

  it('handles WebSocket initialization failure and renders it in AskKratisView', async () => {
    ;(
      globalThis as typeof globalThis & { mockWebSocketShouldThrow?: boolean }
    ).mockWebSocketShouldThrow = true

    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    renderWithRouter(['/ask'])

    // Explicitly trigger connect to invoke catch block
    act(() => {
      useWebSocketStore.getState().connect()
    })

    // Wait for the error text in the UI
    await waitFor(() => {
      expect(screen.getByText('Failed to initialize connection')).toBeInTheDocument()
    })
  })

  it('invalidates ingestion-scoped query cache on ingestion event', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const ws = setupConnected()

    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            event: {
              repositoryId: 'repo-1',
              status: 'COMPLETED',
            },
            type: 'ingestion',
          },
        }),
      })
    })

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ['batch-history', 'team-1', 'repo-1'],
      })
    })
  })

  it('keeps retrying with capped backoff after repeated disconnects', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    renderWithRouter(['/ask'])

    // Wait for the component to mount first
    await waitFor(() => {
      expect(screen.getByText('Connecting to Kratis...')).toBeInTheDocument()
    })

    // Now enable fake timers
    vi.useFakeTimers()

    setupConnected()
    const instancesAfterConnect = allInstances.length

    // Trigger onclose and run the scheduled reconnect well past the old 5-attempt limit
    for (let i = 0; i < 8; i++) {
      act(() => {
        const latestWs = allInstances[allInstances.length - 1]
        latestWs.onclose?.({} as CloseEvent)
        vi.runOnlyPendingTimers()
      })
    }

    // Now restore real timers so waitFor can poll
    vi.useRealTimers()

    // Reconnection continues beyond the previous limit instead of giving up permanently
    expect(allInstances.length).toBeGreaterThan(instancesAfterConnect + 5)
    expect(useWebSocketStore.getState().error).toBe('Connection lost. Reconnecting…')
  })

  it('refetches active queries after a reconnect to recover missed broadcasts', () => {
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const first = setupConnected()
    invalidateSpy.mockClear()

    // Drop the connection and establish a new one.
    act(() => {
      first.onclose?.({} as CloseEvent)
    })
    act(() => {
      useWebSocketStore.getState().connect()
    })

    const reconnected = allInstances[allInstances.length - 1]
    act(() => {
      reconnected.onopen?.()
    })

    expect(invalidateSpy).toHaveBeenCalled()
  })

  it('re-establishes the team subscription after a reconnect once auth is confirmed', async () => {
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { connect } = useWebSocketStore.getState()
    connect()
    const first = allInstances[allInstances.length - 1]
    act(() => {
      first.onopen?.()
    })
    act(() => {
      first.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: { status: 'authenticated', type: 'auth', userId: 'user-1' },
        }),
      })
    })
    await waitFor(() => {
      expect(sentMethodNames(first)).toContain('subscribe')
    })

    // Server restart: the socket drops and reconnects.
    act(() => {
      first.onclose?.({} as CloseEvent)
    })
    act(() => {
      connect()
    })

    const reconnected = allInstances[allInstances.length - 1]
    act(() => {
      reconnected.onopen?.()
    })

    // The subscribe is not sent speculatively while auth is still unconfirmed.
    expect(sentMethodNames(reconnected)).not.toContain('subscribe')

    act(() => {
      reconnected.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: { status: 'authenticated', type: 'auth', userId: 'user-1' },
        }),
      })
    })

    await waitFor(() => {
      expect(sentMethodNames(reconnected)).toContain('subscribe')
    })
  })

  it('handles edge cases for subscription, telemetry, canvas, and error events', async () => {
    const ws = setupConnected()
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    // 1. WebSocket send error path
    ws.send.mockImplementationOnce(() => {
      throw new Error('Send failed')
    })
    act(() => {
      useWebSocketStore.getState().send('chat.unsubscribe', { chatId: 'sess-1' })
    })
    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('Failed to send request:'),
      expect.any(Error),
    )
    consoleSpy.mockRestore()

    // 2. subscribe to same team twice (triggers return branch)
    act(() => {
      useWebSocketStore.getState().subscribe('team-1')
    })
    act(() => {
      useWebSocketStore.getState().subscribe('team-1')
    })

    // 3. unsubscribe from a non-subscribed team (triggers return branch)
    act(() => {
      useWebSocketStore.getState().unsubscribe('team-2')
    })

    // 4. WebSocket error event
    act(() => {
      ws.onerror?.({})
    })
    expect(useWebSocketStore.getState().error).toBe('Failed to connect to server')

    // 5. telemetry message result
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            chatId: 'chat-1',
            event: { status: 'thinking' },
            type: 'telemetry',
          },
        }),
      })
    })

    // 6. canvas message result
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            event: {
              chatId: 'sess-1',
              content: 'Content',
              documentId: 'doc-1',
              title: 'Title',
              version: 1,
            },
            type: 'canvas',
          },
        }),
      })
    })

    // 7. auth failure error message (id === 0)
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          error: { code: -32000, message: 'Authentication failed' },
          id: 0,
          jsonrpc: '2.0',
        }),
      })
    })
    await waitFor(() => {
      expect(useWebSocketStore.getState().error).toBe('Authentication failed')
    })

    // 8. session-scoped error
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          error: {
            code: 500,
            data: { chatId: 'test-session' },
            message: 'Session error',
          },
          id: 1,
          jsonrpc: '2.0',
        }),
      })
    })

    // 9. global error/toast error
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          error: {
            code: 500,
            data: { chatId: 'non-existent-session' },
            message: 'Global error message',
          },
          id: 2,
          jsonrpc: '2.0',
        }),
      })
    })

    // 10. subscription confirmation type
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            type: 'subscription',
          },
        }),
      })
    })
  })
})
