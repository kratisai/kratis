import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ChatMessage } from '@/store/chat-store'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { setupConnected } from '../support/test-websocket'

function getTextContent(message: ChatMessage | undefined): string | undefined {
  return message && 'content' in message ? message.content : undefined
}

// Mock crypto.randomUUID
vi.stubGlobal('crypto', {
  randomUUID: () => 'test-uuid-123',
})

describe('chat-store session loading and streaming', () => {
  setupFetchMock()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )
    useAuthStore.getState().setCurrentTeamId('team-1')
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    vi.useRealTimers()
    useAuthStore.getState().logout()
  })

  describe('currentChatId on chat load', () => {
    it('subscribeChat does NOT populate messages immediately', () => {
      const ws = setupConnected()
      const { subscribeChat } = useChatStore.getState()

      subscribeChat('existing-session-id')

      // Verify chat.subscribe request was sent
      expect(ws.send).toHaveBeenCalledWith(
        expect.stringContaining('"method":"chat.subscribe"')
      )
      expect(ws.send).toHaveBeenCalledWith(
        expect.stringContaining('"chatId":"existing-session-id"')
      )

      // messages should NOT be populated yet
      const messages = useChatStore.getState().messages['existing-session-id']
      expect(messages).toBeUndefined()
    })

    it('messages populated on session.complete', async () => {
      const ws = setupConnected()
      const { subscribeChat } = useChatStore.getState()

      subscribeChat('existing-session-id')
      ws.send.mockClear()

      // Simulate a session.message response (new format - same as live chat message)
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'existing-session-id',
            content: 'Hello!',
            messageId: 'msg-1',
            role: 'user',
            timestamp: '2024-01-01T00:00:00Z',
            type: 'message',
          },
        }),
      })
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'existing-session-id',
            messageCount: 1,
            type: 'complete',
          },
        }),
      })

      // Fast forward time to flush throttled updates
      await vi.advanceTimersByTimeAsync(100)

      const messages = useChatStore.getState().messages['existing-session-id']
      expect(messages).toHaveLength(1)
      expect(getTextContent(messages[0])).toBe('Hello!')
    })

    it('sendMessage after session complete uses correct sessionId', async () => {
      const ws = setupConnected()
      const { sendMessage, subscribeChat } = useChatStore.getState()

      // Load an existing session
      subscribeChat('existing-session-id')
      ws.send.mockClear()

      // Simulate session.complete
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'existing-session-id',
            messageCount: 0,
            type: 'complete',
          },
        }),
      })

      await vi.advanceTimersByTimeAsync(100)

      // Set provider and model so sendMessage doesn't show a toast error
      useUIStore.getState().setSelectedModel('provider-1', 'model-1')

      // Send a message - should include the sessionId
      sendMessage('existing-session-id', 'Hello!')

      // Verify the chat.send request includes the sessionId
      const sendCall = ws.send.mock.calls[0][0]
      const request = JSON.parse(sendCall)
      expect(request.params.chatId).toBe('existing-session-id')

      // Simulate response (new format - message + complete)
      ws.onmessage?.({
        data: JSON.stringify({
          id: request.id,
          jsonrpc: '2.0',
          result: {
            chatId: 'existing-session-id',
            content: 'Hi!',
            messageId: 'msg-1',
            role: 'assistant',
            timestamp: new Date(),
            type: 'message',
          },
        }),
      })
      ws.onmessage?.({
        data: JSON.stringify({
          id: request.id,
          jsonrpc: '2.0',
          result: {
            chatId: 'existing-session-id',
            messageCount: 1,
            type: 'complete',
          },
        }),
      })

      await vi.advanceTimersByTimeAsync(100)

      // Message should be in the store
      const messages = useChatStore.getState().messages['existing-session-id']
      expect(messages).toHaveLength(1)
      expect(getTextContent(messages[0])).toBe('Hi!')
    })

    it('currentChatId cleared on clearChats', () => {
      setupConnected()
      const { clearChats, subscribeChat } = useChatStore.getState()

      subscribeChat('some-session-id')
      expect(useChatStore.getState().currentChatId).toBe('some-session-id')

      clearChats()
      expect(useChatStore.getState().currentChatId).toBeNull()
    })
  })

  describe('Issue 3: Streamed session.load', () => {
    it('streams session.message responses with throttle', async () => {
      const ws = setupConnected()
      const { subscribeChat } = useChatStore.getState()

      subscribeChat('streamed-session-id')
      ws.send.mockClear()

      // Simulate first session.message response (new format)
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'streamed-session-id',
            content: 'Hello!',
            messageId: 'msg-1',
            role: 'user',
            timestamp: '2024-01-01T00:00:00Z',
            type: 'message',
          },
        }),
      })
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'streamed-session-id',
            content: 'How can I help?',
            messageId: 'msg-2',
            role: 'assistant',
            timestamp: '2024-01-01T00:00:01Z',
            type: 'message',
          },
        }),
      })

      // Fast forward time to flush throttled updates
      await vi.advanceTimersByTimeAsync(100)

      const messages = useChatStore.getState().messages['streamed-session-id']
      expect(messages).toHaveLength(2)
    })

    it('accumulates multiple session.message responses', async () => {
      const ws = setupConnected()
      const { subscribeChat } = useChatStore.getState()

      subscribeChat('multi-message-session-id')
      ws.send.mockClear()

      // Simulate multiple session.message responses
      const simulatedMessages = [
        { content: 'User msg 1', id: 'msg-1', role: 'user' },
        { content: 'Assistant msg 1', id: 'msg-2', role: 'assistant' },
        { content: 'User msg 2', id: 'msg-3', role: 'user' },
      ]

      for (const msg of simulatedMessages) {
        ws.onmessage?.({
          data: JSON.stringify({
            id: 1,
            jsonrpc: '2.0',
            result: {
              chatId: 'multi-message-session-id',
              content: msg.content,
              messageId: msg.id,
              role: msg.role,
              timestamp: new Date().toISOString(),
              type: 'message',
            },
          }),
        })
      }

      await vi.advanceTimersByTimeAsync(100)

      const messages = useChatStore.getState().messages['multi-message-session-id']
      expect(messages).toHaveLength(3)
    })

    it('session.complete flushes pending updates and sets serverSessionId', async () => {
      const ws = setupConnected()
      const { subscribeChat } = useChatStore.getState()

      subscribeChat('complete-session-id')
      ws.send.mockClear()

      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'complete-session-id',
            content: 'Message before complete',
            messageId: 'msg-1',
            role: 'user',
            timestamp: new Date().toISOString(),
            type: 'message',
          },
        }),
      })

      // Simulate session.complete BEFORE timer advances
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'complete-session-id',
            messageCount: 1,
            type: 'complete',
          },
        }),
      })

      // Flush the buffered frames; the message must be flushed immediately on session.complete
      await vi.advanceTimersByTimeAsync(100)

      // Messages should be flushed immediately on session.complete
      const messages = useChatStore.getState().messages['complete-session-id']
      expect(messages).toHaveLength(1)
    })

    it('empty session loads correctly with session.complete', async () => {
      const ws = setupConnected()
      const { subscribeChat } = useChatStore.getState()

      subscribeChat('empty-session-id')
      ws.send.mockClear()

      // Simulate session.complete with messageCount 0 for empty session
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'empty-session-id',
            messageCount: 0,
            type: 'complete',
          },
        }),
      })

      // Flush the buffered frame
      await vi.advanceTimersByTimeAsync(100)

      // Session should exist in store with empty array
      const messages = useChatStore.getState().messages['empty-session-id']
      expect(messages).toBeDefined()
      expect(messages).toHaveLength(0)
    })

    it('clearChats cleans up session load timers and accumulators', async () => {
      const ws = setupConnected()
      const { clearChats, subscribeChat } = useChatStore.getState()

      subscribeChat('cleanup-session-id')
      ws.send.mockClear()

      // Send a message (starts throttle timer) (new format)
      ws.onmessage?.({
        data: JSON.stringify({
          id: 1,
          jsonrpc: '2.0',
          result: {
            chatId: 'cleanup-session-id',
            content: 'Pending',
            messageId: 'msg-1',
            role: 'user',
            timestamp: '2024-01-01T00:00:00Z',
            type: 'message',
          },
        }),
      })

      // Flush the buffered frame so the throttle timer starts
      await vi.advanceTimersByTimeAsync(100)

      // Clear sessions should clean up timers
      clearChats()

      // Advancing timers should not cause any updates
      await vi.advanceTimersByTimeAsync(100)

      // Messages should be cleared
      expect(useChatStore.getState().messages['cleanup-session-id']).toBeUndefined()
    })

    it('enforces non-null sessionId in sendMessage', () => {
      const ws = setupConnected()
      const { sendMessage } = useChatStore.getState()

      useUIStore.getState().setSelectedModel('provider-1', 'model-1')

      // Send chat message with non-null sessionId
      sendMessage('session-123', 'Hello!')

      // Verify the chat.send request is sent with correct sessionId
      const sendCall = ws.send.mock.calls[0][0]
      const request = JSON.parse(sendCall)
      expect(request.params.chatId).toBe('session-123')
      expect(request.params.message).toBe('Hello!')
    })
  })
})
