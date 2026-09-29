import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ChatMessage } from '@/store/chat-store'
import type { MessageChunkResult, MessageResult } from '@/types/websocket-types'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'

import { setupFetchMock } from '../support/test-fetch-mocks'

function getTextContent(message: ChatMessage | undefined): string | undefined {
  return message && 'content' in message ? message.content : undefined
}

describe('chat-store protocol determinism and deduplication', () => {
  setupFetchMock()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )
    useAuthStore.getState().setCurrentTeamId('team-1')
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    vi.useRealTimers()
    useAuthStore.getState().logout()
  })

  it('handleMessageResult immediately populates messages in store', () => {
    const chatId = 'chat-100'
    const messageResult: MessageResult = {
      chatId,
      content: 'Replayed user prompt',
      messageId: 'msg-user-1',
      role: 'user',
      timestamp: '2026-07-26T12:00:00Z',
      type: 'message',
    }

    useChatStore.getState().handleMessageResult(messageResult)

    const messages = useChatStore.getState().messages[chatId]
    expect(messages).toBeDefined()
    expect(messages).toHaveLength(1)
    expect(messages[0]).toEqual({
      content: 'Replayed user prompt',
      id: 'msg-user-1',
      role: 'user',
      timestamp: new Date('2026-07-26T12:00:00Z'),
    })
  })

  it('handleMessageResult deduplicates messages with the same messageId', () => {
    const chatId = 'chat-101'
    const messageResult: MessageResult = {
      chatId,
      content: 'Original prompt',
      messageId: 'msg-user-1',
      role: 'user',
      timestamp: '2026-07-26T12:00:00Z',
      type: 'message',
    }

    useChatStore.getState().handleMessageResult(messageResult)
    // Send identical message again
    useChatStore.getState().handleMessageResult(messageResult)

    const messages = useChatStore.getState().messages[chatId]
    expect(messages).toHaveLength(1)
  })

  it('handleMessageChunkResult incrementally appends text to single assistant message card', async () => {
    const chatId = 'chat-200'
    useChatStore.setState({ currentChatId: chatId })

    const chunk1: MessageChunkResult = {
      chatId,
      content: 'Hello ',
      messageId: 'msg-asst-1',
      role: 'assistant',
      timestamp: '2026-07-26T12:00:01Z',
      type: 'message_chunk',
    }

    const chunk2: MessageChunkResult = {
      chatId,
      content: 'world!',
      messageId: 'msg-asst-1',
      role: 'assistant',
      timestamp: '2026-07-26T12:00:02Z',
      type: 'message_chunk',
    }

    useChatStore.getState().handleMessageChunkResult(chunk1)
    useChatStore.getState().handleMessageChunkResult(chunk2)

    // Advance timer to flush throttled update
    await vi.advanceTimersByTimeAsync(100)

    const messages = useChatStore.getState().messages[chatId]
    expect(messages).toHaveLength(1)
    expect(getTextContent(messages[0])).toBe('Hello world!')
  })

  it('receiving completeResult for history or stream does NOT wipe existing messages', () => {
    const chatId = 'chat-300'
    useChatStore.getState().handleMessageResult({
      chatId,
      content: 'Persisted user message',
      messageId: 'msg-user-1',
      role: 'user',
      timestamp: '2026-07-26T12:00:00Z',
      type: 'message',
    })

    // Complete replay phase
    useChatStore.getState().handleCompleteResult({
      chatId,
      messageCount: 1,
      type: 'complete',
    })

    let messages = useChatStore.getState().messages[chatId]
    expect(messages).toHaveLength(1)
    expect(getTextContent(messages[0])).toBe('Persisted user message')

    // Complete stream phase
    useChatStore.getState().handleCompleteResult({
      messageId: 'msg-asst-1',
      type: 'complete',
    })

    messages = useChatStore.getState().messages[chatId]
    expect(messages).toHaveLength(1)
  })
})
