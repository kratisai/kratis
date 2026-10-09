import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { setupConnected } from '../support/test-websocket'

// Mock crypto.randomUUID
vi.stubGlobal('crypto', {
  randomUUID: () => 'test-uuid-123',
})

function emitFrame(ws: { onmessage: ((event: { data: string }) => void) | null }, result: object) {
  ws.onmessage?.({ data: JSON.stringify({ id: 1, jsonrpc: '2.0', result }) })
}

describe('chat-store send-in-flight state', () => {
  setupFetchMock()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    useAuthStore
      .getState()
      .login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600,
      )
    useAuthStore.getState().setCurrentTeamId('team-1')
    useUIStore.getState().setSelectedModel('provider-1', 'model-1')
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    vi.useRealTimers()
    useAuthStore.getState().logout()
  })

  it('sendMessage marks the chat as sending', () => {
    setupConnected()

    useChatStore.getState().sendMessage('session-1', 'Hello')

    expect(useChatStore.getState().sendingChatIds.has('session-1')).toBe(true)
  })

  it('markSending marks the chat as sending for a new chat', () => {
    useChatStore.getState().markSending('new-chat-1')

    expect(useChatStore.getState().sendingChatIds.has('new-chat-1')).toBe(true)
  })

  it('complete result clears a markSending state', async () => {
    const ws = setupConnected()

    useChatStore.getState().markSending('new-chat-1')
    emitFrame(ws, {
      chatId: 'new-chat-1',
      messageCount: 1,
      messageId: 'assistant-1',
      type: 'complete',
    })

    await vi.advanceTimersByTimeAsync(100)

    expect(useChatStore.getState().sendingChatIds.has('new-chat-1')).toBe(false)
  })

  it('complete result clears the sending state and leaves the assistant response in place', async () => {
    const ws = setupConnected()

    useChatStore.getState().sendMessage('session-1', 'Hello')
    emitFrame(ws, {
      chatId: 'session-1',
      content: 'Here is a response',
      messageId: 'assistant-1',
      role: 'assistant',
      timestamp: new Date().toISOString(),
      type: 'message',
    })
    emitFrame(ws, {
      chatId: 'session-1',
      messageCount: 1,
      messageId: 'assistant-1',
      type: 'complete',
    })

    await vi.advanceTimersByTimeAsync(100)

    expect(useChatStore.getState().sendingChatIds.has('session-1')).toBe(false)
    const messages = useChatStore.getState().messages['session-1'] ?? []
    const lastMessage = messages.at(-1)
    expect(lastMessage?.role).toBe('assistant')
  })

  it('complete result without messageId (history replay marker) does not clear other sends', () => {
    const ws = setupConnected()

    useChatStore.getState().sendMessage('session-1', 'Hello')
    emitFrame(ws, { chatId: 'session-2', messageCount: 1, type: 'complete' })

    expect(useChatStore.getState().sendingChatIds.has('session-1')).toBe(true)
  })

  it('chat_error result clears the sending state', async () => {
    const ws = setupConnected()
    useChatStore.getState().subscribeChat('session-1')

    useChatStore.getState().sendMessage('session-1', 'Hello')
    emitFrame(ws, {
      chatId: 'session-1',
      code: 500,
      message: 'Agent loop failed',
      messageId: 'assistant-1',
      type: 'chat_error',
    })
    await vi.advanceTimersByTimeAsync(50)

    expect(useChatStore.getState().sendingChatIds.has('session-1')).toBe(false)
  })

  it('handleDisconnect clears all sending state', () => {
    setupConnected()

    useChatStore.getState().sendMessage('session-1', 'Hello')
    useChatStore.getState().sendMessage('session-2', 'Hello')
    expect(useChatStore.getState().sendingChatIds.size).toBe(2)

    useChatStore.getState().handleDisconnect()
    expect(useChatStore.getState().sendingChatIds.size).toBe(0)
  })

  it('clearChats clears the sending state', () => {
    setupConnected()

    useChatStore.getState().sendMessage('session-1', 'Hello')
    useChatStore.getState().clearChats()
    expect(useChatStore.getState().sendingChatIds.size).toBe(0)
  })
})
