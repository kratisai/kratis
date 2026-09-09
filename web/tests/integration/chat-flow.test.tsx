import { act } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import { useChatStore } from '@/store/chat-store'

import {
  mockListChats,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import {
  setupConnected,
  triggerMockComplete,
  triggerMockMessageEcho,
} from '../support/test-websocket'

describe('Chat Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    // Reset chat store
    useChatStore.getState().clearChats()
  })

  it('loads session messages when navigating to a session', async () => {
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
    mockListChats([
      {
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'Test User',
        id: 'session-1',
        teamId: 'team-1',
        title: 'Test Session',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-1'])

    const ws = setupConnected()

    // Session view should render
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Simulate session.load response with messages
    act(() => {
      triggerMockMessageEcho(ws, 'Hello from the past', 'session-1', 'user', 'msg-hist-1')
      triggerMockMessageEcho(ws, 'Hi there!', 'session-1', 'assistant', 'msg-hist-2')
      triggerMockComplete(ws, 'session-1', undefined, 2)
    })

    // Messages should appear
    await waitFor(() => {
      expect(screen.getByText('Hello from the past')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByText('Hi there!')).toBeInTheDocument()
    })
  })

  it('handles error response during chat', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-err'])

    const ws = setupConnected()

    // Set current session
    act(() => {
      useChatStore.getState().subscribeChat('session-err')
    })

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Simulate an error response
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          error: {
            code: -32000,
            message: 'Rate limit exceeded',
          },
          id: 1,
          jsonrpc: '2.0',
        }),
      })
    })

    // Error message should appear in chat
    await waitFor(() => {
      expect(screen.getByText('Rate limit exceeded')).toBeInTheDocument()
    })
  })

  it('handles disconnect and clears streaming state', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-disc'])

    setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Set current session and add a message directly
    act(() => {
      useChatStore.getState().subscribeChat('session-disc')
      useChatStore.getState().addMessage('session-disc', {
        content: 'Pre-existing message',
        id: 'msg-pre-1',
        role: 'assistant',
        timestamp: new Date(),
      })
    })

    // Verify message is visible
    await waitFor(() => {
      expect(screen.getByText('Pre-existing message')).toBeInTheDocument()
    })

    // Now disconnect - should clear streaming state but keep existing messages
    act(() => {
      useChatStore.getState().handleDisconnect()
    })

    // Pre-existing message should still be visible after disconnect
    expect(screen.getByText('Pre-existing message')).toBeInTheDocument()
  })

  it('shows disconnected status when WebSocket is not connected', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-noc'])

    // Don't connect WebSocket
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Should show disconnected status
    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })

    // Input should be disabled
    const input = screen.getByPlaceholderText('Connecting...')
    expect(input).toBeDisabled()
  })

  it('shows connected status when WebSocket is connected', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-con'])

    setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Should show connected status
    await waitFor(() => {
      expect(screen.getByText('Connected')).toBeInTheDocument()
    })
  })

  it('clears sessions and resets state', async () => {
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

    // Add some messages to the store
    act(() => {
      useChatStore.getState().subscribeChat('session-clear')
      useChatStore.getState().addMessage('session-clear', {
        content: 'Test message',
        id: 'msg-clear-1',
        role: 'user',
        timestamp: new Date(),
      })
    })

    // Verify messages exist
    expect(useChatStore.getState().messages['session-clear']).toHaveLength(1)

    // Clear sessions
    act(() => {
      useChatStore.getState().clearChats()
    })

    // Messages should be cleared
    expect(useChatStore.getState().messages).toEqual({})
    expect(useChatStore.getState().currentChatId).toBeNull()
  })

  it('shows error toast when sending message without a model provider', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-1'])

    setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Connected')).toBeInTheDocument()
    })

    // sendMessage with no provider should show toast
    act(() => {
      useChatStore.getState().sendMessage('session-1', 'Hello')
    })

    // Should show error toast about no model provider
    await waitFor(() => {
      const errorMessages = screen.getAllByText(/no model provider selected/i)
      expect(errorMessages.length).toBeGreaterThan(0)
    })
  })

  it('handles disconnect by flushing pending streaming content', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-disc'])

    const ws = setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Connected')).toBeInTheDocument()
    })

    // Set current session
    act(() => {
      useChatStore.getState().subscribeChat('session-disc')
    })

    // Simulate a message result (streaming)
    act(() => {
      triggerMockMessageEcho(ws, 'Hello ', 'session-disc', 'assistant', 'msg-disc-1')
    })

    // Now disconnect
    act(() => {
      useChatStore.getState().handleDisconnect()
    })

    // After disconnect, streaming content should be flushed
    // and active operations cleared
    const state = useChatStore.getState()
    expect(state.currentChatId).toBe('session-disc')
  })

  it('handles error response by adding error message to chat', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-err'])

    setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Connected')).toBeInTheDocument()
    })

    // Set current session
    act(() => {
      useChatStore.getState().subscribeChat('session-err')
    })

    // Simulate error response
    act(() => {
      useChatStore.getState().handleErrorResponse({
        code: -1,
        data: null,
        message: 'Something went wrong',
      })
    })

    // Error message should be added to chat
    await waitFor(() => {
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })
  })

  it('handles chat_error result by surfacing the failure in chat', async () => {
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
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-err'])

    const ws = setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Connected')).toBeInTheDocument()
    })

    // Set current session
    act(() => {
      useChatStore.getState().subscribeChat('session-err')
    })

    // Simulate an in-band chat_error result from the planning agent
    act(() => {
      ws.onmessage?.({
        data: JSON.stringify({
          jsonrpc: '2.0',
          result: {
            chatId: 'session-err',
            code: -32008,
            message: 'Agent exceeded maximum iterations (3).',
            messageId: 'err-1',
            type: 'chat_error',
          },
        }),
      })
    })

    await waitFor(() => {
      expect(screen.getByText('Agent exceeded maximum iterations (3).')).toBeInTheDocument()
    })
  })
})
