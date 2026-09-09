import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { WorkingChatMessage } from '@/store/chat-store'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'

import {
  setupFetchMock,
} from '../support/test-fetch-mocks'

vi.stubGlobal('crypto', {
  randomUUID: () => 'test-uuid-123',
})

function getWorkingMessages(chatId: string): WorkingChatMessage[] {
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- messages[chatId] can be undefined at runtime despite the Record type
  return (useChatStore.getState().messages[chatId] || []).filter(
    (m): m is WorkingChatMessage => m.role === 'working',
  )
}

describe('chat-store - working message lifecycle', () => {
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
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    vi.useRealTimers()
    useAuthStore.getState().logout()
  })

  it('does not create a working message until the first telemetry event arrives', () => {
    const chatId = 'chat-1'
    expect(getWorkingMessages(chatId)).toHaveLength(0)
  })

  it('lazily creates a working message on the first telemetry event', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Evaluating...' },
      type: 'telemetry',
    })

    const workingMessages = getWorkingMessages(chatId)
    expect(workingMessages).toHaveLength(1)
    expect(workingMessages[0].isStreaming).toBe(true)
    expect(workingMessages[0].startTime).not.toBeNull()
    expect(workingMessages[0].items).toHaveLength(1)
    expect(workingMessages[0].items[0].type).toBe('thought')
  })

  it('adds thought items to the open working message', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Evaluating...' },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(1)
    if (working.items[0].type === 'thought') {
      expect(working.items[0].text).toBe('Evaluating...')
    }
  })

  it('adds tool items to the open working message', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: {
        taskId: 'task-1',
        thought: 'Searching for info',
        toolName: 'web_search',
      },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(1)
    expect(working.items[0].type).toBe('tool')
    if (working.items[0].type === 'tool') {
      expect(working.items[0].taskId).toBe('task-1')
      expect(working.items[0].toolName).toBe('web_search')
      expect(working.items[0].thought).toBe('Searching for info')
      expect(working.items[0].status).toBe('running')
    }
  })

  it('updates tool status to complete on the open working message', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: {
        taskId: 'task-1',
        thought: 'Searching',
        toolName: 'web_search',
      },
      type: 'telemetry',
    })

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: {
        status: 'success',
        taskId: 'task-1',
      },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(1)
    if (working.items[0].type === 'tool') {
      expect(working.items[0].status).toBe('complete')
    }
  })

  it('updates tool status to error on the open working message', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: {
        taskId: 'task-1',
        thought: 'Searching',
        toolName: 'web_search',
      },
      type: 'telemetry',
    })

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: {
        error: 'Timeout',
        taskId: 'task-1',
      },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(1)
    if (working.items[0].type === 'tool') {
      expect(working.items[0].status).toBe('error')
      expect(working.items[0].errorMessage).toBe('Timeout')
    }
  })

  it('clears working messages on clearChats', () => {
    const chatId = 'chat-1'
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Thinking...' },
      type: 'telemetry',
    })

    expect(getWorkingMessages(chatId)).toHaveLength(1)

    useChatStore.getState().clearChats()

    expect(useChatStore.getState().messages).toEqual({})
  })

  it('maintains separate working messages for different chats', () => {
    const chatId1 = 'chat-1'
    const chatId2 = 'chat-2'

    useChatStore.getState().handleTelemetryEvent(chatId1, {
      chatId: chatId1,
      event: { text: 'First thought' },
      type: 'telemetry',
    })

    useChatStore.getState().handleTelemetryEvent(chatId2, {
      chatId: chatId2,
      event: { text: 'Second thought' },
      type: 'telemetry',
    })

    const working1 = getWorkingMessages(chatId1)[0]
    const working2 = getWorkingMessages(chatId2)[0]

    expect(working1.items).toHaveLength(1)
    expect(working2.items).toHaveLength(1)
    if (working1.items[0].type === 'thought' && working2.items[0].type === 'thought') {
      expect(working1.items[0].text).toBe('First thought')
      expect(working2.items[0].text).toBe('Second thought')
    }
  })

  it('closes the open working message via finishWorking', () => {
    const chatId = 'chat-1'
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Thinking...' },
      type: 'telemetry',
    })

    const before = getWorkingMessages(chatId)[0]
    expect(before.isStreaming).toBe(true)
    expect(before.endTime).toBeNull()

    useChatStore.getState().finishWorking(chatId)

    const after = getWorkingMessages(chatId)[0]
    expect(after.isStreaming).toBe(false)
    expect(after.endTime).not.toBeNull()
    expect(after.items).toHaveLength(1)
  })

  it('starts a new working message when a telemetry event arrives after the previous one was closed', () => {
    const chatId = 'chat-1'

    // First turn
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'First turn thinking' },
      type: 'telemetry',
    })
    useChatStore.getState().finishWorking(chatId)

    expect(getWorkingMessages(chatId)).toHaveLength(1)
    expect(getWorkingMessages(chatId)[0].isStreaming).toBe(false)

    // Second turn - a new telemetry event should create a brand new working message,
    // not reopen or append to the closed one.
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Second turn thinking' },
      type: 'telemetry',
    })

    const workingMessages = getWorkingMessages(chatId)
    expect(workingMessages).toHaveLength(2)
    expect(workingMessages[0].isStreaming).toBe(false)
    expect(workingMessages[1].isStreaming).toBe(true)
    if (
      workingMessages[0].items[0].type === 'thought' &&
      workingMessages[1].items[0].type === 'thought'
    ) {
      expect(workingMessages[0].items[0].text).toBe('First turn thinking')
      expect(workingMessages[1].items[0].text).toBe('Second turn thinking')
    }
  })

  it('creates a thought item from the first Thought event', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Weighing the ' },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(1)
    expect(working.items[0].type).toBe('thought')
    if (working.items[0].type === 'thought') {
      expect(working.items[0].text).toBe('Weighing the ')
    }
  })

  it('appends successive Thought events onto the same thought bullet', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Weighing the ' },
      type: 'telemetry',
    })
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'trade-offs of ' },
      type: 'telemetry',
    })
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'each approach...' },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    // Still a single bullet, not three - deltas accumulate rather than creating new items.
    expect(working.items).toHaveLength(1)
    if (working.items[0].type === 'thought') {
      expect(working.items[0].text).toBe('Weighing the trade-offs of each approach...')
    }
  })

  it('starts a new thought bullet for a Thought after a tool call interrupts the thinking', () => {
    const chatId = 'chat-1'

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'First, I should check the repo.' },
      type: 'telemetry',
    })
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { taskId: 'task-1', thought: 'Checking repo', toolName: 'search_repo' },
      type: 'telemetry',
    })
    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Now let me consider the results.' },
      type: 'telemetry',
    })

    const working = getWorkingMessages(chatId)[0]
    expect(working.items).toHaveLength(3)
    expect(working.items[0].type).toBe('thought')
    expect(working.items[1].type).toBe('tool')
    expect(working.items[2].type).toBe('thought')
    if (working.items[0].type === 'thought' && working.items[2].type === 'thought') {
      expect(working.items[0].text).toBe('First, I should check the repo.')
      expect(working.items[2].text).toBe('Now let me consider the results.')
    }
  })

  it('appends the working message after existing chat messages, preserving order', () => {
    const chatId = 'chat-1'
    useChatStore.getState().addMessage(chatId, {
      content: 'Hello',
      id: 'user-1',
      role: 'user',
      timestamp: new Date(),
    })

    useChatStore.getState().handleTelemetryEvent(chatId, {
      chatId: chatId,
      event: { text: 'Thinking...' },
      type: 'telemetry',
    })

    const messages = useChatStore.getState().messages[chatId]
    expect(messages).toHaveLength(2)
    expect(messages[0].role).toBe('user')
    expect(messages[1].role).toBe('working')
  })
})
