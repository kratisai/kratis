import { act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// NOW import the stores and components
import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { mockListChats, mockListTeams, setupFetchMock } from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import { setupConnected } from '../support/test-websocket'

describe('Chat View Flow Integration', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    mockListChats([])
    vi.clearAllMocks()
    useChatStore.getState().clearChats()
    useUIStore.setState({
      selectedModelName: 'gpt-4',
      selectedProviderId: 'provider-1',
    })
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
  })

  it('sends a message and triggers WebSocket send with correct payload', async () => {
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
    const { user } = renderWithRouter(['/chats/test-session'])

    // Wait for the chat view to load
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // Wait for the input to be enabled (isConnected becomes true)
    await waitFor(() => {
      const input = screen.getByPlaceholderText(/Type a message/i)
      expect(input).not.toBeDisabled()
    })

    // Find the message input and type a message
    const input = screen.getByPlaceholderText(/Type a message/i)
    await user.type(input, 'Hello Kratis')

    // Press Enter to send
    await user.keyboard('{Enter}')

    // Verify the WebSocket send was called with correct payload
    await waitFor(() => {
      expect(ws.send).toHaveBeenCalledWith(expect.stringContaining('"method":"chat.send"'))
      const chatSend = ws.send.mock.calls
        .map(
          ([payload]) =>
            JSON.parse(payload as string) as { method: string; params: Record<string, unknown> },
        )
        .find((request) => request.method === 'chat.send')
      expect(chatSend?.params.message).toBe('Hello Kratis')
      expect(chatSend?.params.chatId).toBe('test-session')
      expect(chatSend?.params.teamId).toBe('team-1')
    })

    // Verify the user message appears in the UI immediately
    await waitFor(() => {
      expect(screen.getByText('Hello Kratis')).toBeInTheDocument()
    })
  })

  it('displays user message and assistant streaming response from WebSocket', async () => {
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
    const { user } = renderWithRouter(['/chats/test-session'])

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // Wait for the input to be enabled
    await waitFor(() => {
      const input = screen.getByPlaceholderText(/Type a message/i)
      expect(input).not.toBeDisabled()
    })

    // Type and send message
    const input = screen.getByPlaceholderText(/Type a message/i)
    await user.type(input, 'Hello')
    await user.keyboard('{Enter}')

    // Simulate assistant streaming response
    ws.onmessage?.({
      data: JSON.stringify({
        id: 1,
        jsonrpc: '2.0',
        result: {
          chatId: 'test-session',
          content: 'Hello ',
          messageId: 'assistant-msg-1',
          role: 'assistant',
          timestamp: new Date().toISOString(),
          type: 'message',
        },
      }),
    })

    ws.onmessage?.({
      data: JSON.stringify({
        id: 1,
        jsonrpc: '2.0',
        result: {
          chatId: 'test-session',
          content: 'there!',
          messageId: 'assistant-msg-1',
          role: 'assistant',
          timestamp: new Date().toISOString(),
          type: 'message',
        },
      }),
    })

    ws.onmessage?.({
      data: JSON.stringify({
        id: 1,
        jsonrpc: '2.0',
        result: {
          chatId: 'test-session',
          messageId: 'assistant-msg-1',
          type: 'complete',
        },
      }),
    })

    // Verify the assistant message appears in the UI
    await waitFor(() => {
      expect(screen.getByText('Hello')).toBeInTheDocument()
    })
  })

  it('shows error message in UI when WebSocket returns an error response', async () => {
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
    const { user } = renderWithRouter(['/chats/test-session'])

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // Wait for the input to be enabled
    await waitFor(() => {
      const input = screen.getByPlaceholderText(/Type a message/i)
      expect(input).not.toBeDisabled()
    })

    // Type and send message
    const input = screen.getByPlaceholderText(/Type a message/i)
    await user.type(input, 'Hello')
    await user.keyboard('{Enter}')

    // Simulate WebSocket error response for the current chat
    ws.onmessage?.({
      data: JSON.stringify({
        error: {
          code: -32000,
          data: { chatId: 'test-session' },
          message: 'Failed to process message',
        },
        id: 1,
        jsonrpc: '2.0',
      }),
    })

    // Verify the error message appears in the UI
    await waitFor(() => {
      expect(screen.getByText('Failed to process message')).toBeInTheDocument()
    })
  })

  it('shows disconnected state and disables input when WebSocket is not connected', async () => {
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
    renderWithRouter(['/chats/test-session'])

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Don't call setupConnected(), so it remains disconnected
    // Verify disconnected state is shown
    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })

    // Verify input is disabled (placeholder should show "Connecting...")
    const input = screen.getByPlaceholderText(/Connecting\.\.\./i)
    expect(input).toBeDisabled()
  })

  it('toasts error when sending message without a selected model provider', async () => {
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

    // Clear the selected model/provider
    useUIStore.setState({
      selectedModelName: 'gpt-4',
      selectedProviderId: null,
    })

    renderWithRouter(['/chats/test-session'])

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Call sendMessage directly on the store
    act(() => {
      useChatStore.getState().sendMessage('test-session', 'Hello without provider')
    })

    // Verify error toast appears in the DOM
    await waitFor(() => {
      expect(screen.getByText('No model provider selected')).toBeInTheDocument()
    })
  })

  it('toasts error when sending message without a selected model name', async () => {
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

    // Clear the selected model name
    useUIStore.setState({
      selectedModelName: null,
      selectedProviderId: 'provider-1',
    })

    renderWithRouter(['/chats/test-session'])

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Call sendMessage directly on the store
    act(() => {
      useChatStore.getState().sendMessage('test-session', 'Hello without model name')
    })

    // Verify error toast appears in the DOM
    await waitFor(() => {
      expect(screen.getByText('No model selected')).toBeInTheDocument()
    })
  })

  it('toasts error when sending message without a selected team', async () => {
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

    renderWithRouter(['/chats/test-session'])

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Remove team ID from auth store
    useAuthStore.setState({ currentTeamId: null })

    act(() => {
      useChatStore.getState().subscribeChat('test-session')
    })

    act(() => {
      useChatStore.getState().sendMessage('test-session', 'Hello without team')
    })

    await waitFor(() => {
      expect(screen.getByText('No team selected')).toBeInTheDocument()
    })
  })
})
