import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { WorkingChatMessage } from '@/store/chat-store'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useWebSocketStore } from '@/store/websocket-store'

import {
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
import {
  allInstances,
  setupConnected,
} from '../support/test-websocket'

function getWorkingMessages(chatId: string): WorkingChatMessage[] {
  return useChatStore.getState().messages[chatId].filter(
    (m): m is WorkingChatMessage => m.role === 'working',
  )
}

describe('Chat Session Telemetry Flow (Flow D)', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.clearAllMocks()
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
  })

  it('handles network drop, reconnection, and telemetry updates during chat session', async () => {
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

    // 1. User navigates to `/chats/test-session` and is authenticated.
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // 2. Mock WebSocket connects and authenticates successfully.
    const ws = setupConnected()

    // Wait for the input to be enabled (isConnected becomes true)
    await waitFor(() => {
      const input = screen.getByPlaceholderText(/Type a message/i)
      expect(input).not.toBeDisabled()
    })

    // 3. User types a message and sends it.
    const input = screen.getByPlaceholderText(/Type a message/i)
    await user.type(input, 'Hello Kratis')
    await user.keyboard('{Enter}')

    // Verify the user message appears in the UI immediately
    await waitFor(() => {
      expect(screen.getByText('Hello Kratis')).toBeInTheDocument()
    })

    // 4. Simulate a network drop (WebSocket `onclose` event).
    ws.onclose?.()

    // 5. Verify UI shows "Disconnected" or "Connecting..." state.
    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })
    await waitFor(() => {
      const inputAfterDrop = screen.getByPlaceholderText(/Connecting\.\.\./i)
      expect(inputAfterDrop).toBeDisabled()
    })

    // 6. Simulate reconnection by calling connect() again
    useWebSocketStore.getState().connect()
    
    // Find the active WebSocket instance that has the onmessage handler attached by the store
    const activeWs = allInstances.find(ws => ws.onmessage !== null)
    expect(activeWs).toBeDefined()
    
    activeWs!.onopen?.()
    activeWs!.onmessage?.({
      data: JSON.stringify({
        id: 0,
        jsonrpc: '2.0',
        result: { status: 'authenticated', type: 'auth', userId: 'user-1' },
      }),
    })

    // Wait for the input to be enabled again after reconnection
    await waitFor(() => {
      const inputAfterReconnect = screen.getByPlaceholderText(/Type a message/i)
      expect(inputAfterReconnect).not.toBeDisabled()
    })

    // 7. Verify chat history is preserved and new messages can be sent.
    // The previous message should still be there
    expect(screen.getByText('Hello Kratis')).toBeInTheDocument()

    // Send a new message
    const inputAfterReconnect = screen.getByPlaceholderText(/Type a message/i)
    await user.clear(inputAfterReconnect)
    await user.type(inputAfterReconnect, 'Are you still there?')
    await user.keyboard('{Enter}')

    await waitFor(() => {
      expect(screen.getByText('Are you still there?')).toBeInTheDocument()
    })

    // 8. Simulate assistant streaming response with telemetry events. Telemetry is
    // routed by chatId (see chat-store's handleTelemetryEvent), lazily creating a
    // working message on the first event rather than requiring one to be pre-created.
    useChatStore.getState().handleTelemetryEvent('test-session', {
      chatId: 'test-session',
      event: { text: 'Thinking...' },
      type: 'telemetry',
    })
    useChatStore.getState().handleTelemetryEvent('test-session', {
      chatId: 'test-session',
      event: {
        taskId: 'task-1',
        thought: 'Searching for information',
        toolName: 'web_search',
      },
      type: 'telemetry',
    })

    // Assistant message streaming (simulated via store)
    useChatStore.getState().handleMessageResult({
      chatId: 'test-session',
      content: 'Yes, I am still here! How can I help you?',
      messageId: 'assistant-msg-2',
      role: 'assistant',
      timestamp: new Date().toISOString(),
      type: 'message',
    })

    // 9. Verify the working message updates correctly
    const working = getWorkingMessages('test-session')[0]
    expect(working.items).toHaveLength(2)
    expect(working.items[0].type).toBe('thought')
    expect(working.items[1].type).toBe('tool')
    if (working.items[1].type === 'tool') {
      expect(working.items[1].toolName).toBe('web_search')
      expect(working.items[1].status).toBe('running')
    }

    // Complete the message streaming (this finishes the working message)
    useChatStore.getState().handleCompleteResult({
      chatId: 'test-session',
      messageId: 'assistant-msg-2',
      type: 'complete',
    })

    // Verify the assistant message appears in the UI
    await waitFor(() => {
      expect(screen.getByText(/Yes, I am still here!/i)).toBeInTheDocument()
    })

    // Verify the working message is finished
    const workingAfter = getWorkingMessages('test-session')[0]
    expect(workingAfter.isStreaming).toBe(false)
    expect(workingAfter.endTime).not.toBeNull()
  })

  it('should display telemetry progress during chat interaction (UC-26)', () => {
    const chatId = 'chat-uc26'

    // Send telemetry events directly - the working message is created lazily on the
    // first event, not pre-created when the user sends a message.
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId,
      event: { text: 'Evaluating...' },
      type: 'telemetry',
    })

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId,
      event: {
        taskId: 'task-1',
        thought: 'Searching for info',
        toolName: 'web_search',
      },
      type: 'telemetry',
    })

    // Verify the working message is updated
    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(2)
    expect(working.items[0].type).toBe('thought')
    expect(working.items[1].type).toBe('tool')
    if (working.items[0].type === 'thought') {
      expect(working.items[0].text).toBe('Evaluating...')
    }
    if (working.items[1].type === 'tool') {
      expect(working.items[1].taskId).toBe('task-1')
      expect(working.items[1].toolName).toBe('web_search')
      expect(working.items[1].status).toBe('running')
    }
  })

  it('should clear working messages on chat change (UC-27)', () => {
    const chatId = 'chat-uc27'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId,
      event: { text: 'Thinking...' },
      type: 'telemetry',
    })

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId,
      event: {
        taskId: 'task-1',
        thought: 'Searching',
        toolName: 'web_search',
      },
      type: 'telemetry',
    })

    // Verify the working message exists
    expect(getWorkingMessages(chatId)).toHaveLength(1)
    expect(getWorkingMessages(chatId)[0].items).toHaveLength(2)

    // Clear chats - this triggers clearChats in the websocket store
    useChatStore.getState().clearChats()

    // Verify working messages cleared along with everything else
    expect(useChatStore.getState().messages).toEqual({})
  })
})
