import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { WorkingChatMessage } from '@/store/chat-store'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { setupConnected } from '../support/test-websocket'

vi.stubGlobal('crypto', {
  randomUUID: () => 'test-uuid-123',
})

function emitFrame(ws: { onmessage: ((event: { data: string }) => void) | null }, result: object) {
  ws.onmessage?.({ data: JSON.stringify({ id: 1, jsonrpc: '2.0', result }) })
}

describe('chat-store paused state and trailing replay handling', () => {
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

  it('live planning_status paused sets isStreaming=false and isPaused=true on working block', async () => {
    const ws = setupConnected()
    useChatStore.getState().subscribeChat('chat-1')

    useChatStore.getState().handleTelemetryEvent('chat-1', {
      chatId: 'chat-1',
      event: { text: 'Analyzing codebase...' },
      type: 'telemetry',
    })

    const initialWorking = useChatStore
      .getState()
      .messages['chat-1'].find((m) => m.role === 'working') as WorkingChatMessage
    expect(initialWorking.isStreaming).toBe(true)
    expect(initialWorking.isPaused).toBeFalsy()

    emitFrame(ws, {
      chatId: 'chat-1',
      metadata: { directive: 'Continue planning' },
      reason: 'max_iterations',
      status: 'paused',
      type: 'planning_status',
    })

    await vi.advanceTimersByTimeAsync(50)

    const pausedWorking = useChatStore
      .getState()
      .messages['chat-1'].find((m) => m.role === 'working') as WorkingChatMessage
    expect(pausedWorking).toBeDefined()
    expect(pausedWorking.isStreaming).toBe(false)
    expect(pausedWorking.isPaused).toBe(true)
    expect(pausedWorking.pauseReason).toBe('max_iterations')
    expect(useChatStore.getState().sendingChatIds.has('chat-1')).toBe(false)
  })

  it('trailing replay: final trailing planning_status paused sets isPaused=true', async () => {
    const ws = setupConnected()

    // 1. Initial user message
    emitFrame(ws, {
      chatId: 'chat-1',
      content: 'Plan architecture',
      messageId: 'msg-1',
      role: 'user',
      timestamp: new Date().toISOString(),
      type: 'message',
    })

    // 2. Trailing interstitial pause
    emitFrame(ws, {
      chatId: 'chat-1',
      metadata: { directive: 'Continue planning' },
      reason: 'max_iterations',
      status: 'paused',
      type: 'planning_status',
    })

    // 3. CompleteResult (chat history load complete)
    emitFrame(ws, {
      chatId: 'chat-1',
      messageCount: 2,
      type: 'complete',
    })

    await vi.advanceTimersByTimeAsync(50)

    const messages = useChatStore.getState().messages['chat-1'] ?? []
    const working = messages.find((m) => m.role === 'working') as WorkingChatMessage
    expect(working).toBeDefined()
    expect(working.isPaused).toBe(true)
    expect(working.isStreaming).toBe(false)
  })

  it('non-trailing replay: superseded planning_status paused does not remain paused', async () => {
    const ws = setupConnected()

    // 1. User message turn 1
    emitFrame(ws, {
      chatId: 'chat-1',
      content: 'Plan architecture',
      messageId: 'msg-1',
      role: 'user',
      timestamp: new Date().toISOString(),
      type: 'message',
    })

    // 2. Interstitial pause from turn 1
    emitFrame(ws, {
      chatId: 'chat-1',
      metadata: { directive: 'Continue planning' },
      reason: 'max_iterations',
      status: 'paused',
      type: 'planning_status',
    })

    // 3. Subsequent message (e.g. user directive or assistant answer from turn 2)
    emitFrame(ws, {
      chatId: 'chat-1',
      content: 'Here is the completed architecture plan',
      messageId: 'msg-2',
      role: 'assistant',
      timestamp: new Date().toISOString(),
      type: 'message',
    })

    // 4. CompleteResult
    emitFrame(ws, {
      chatId: 'chat-1',
      messageCount: 3,
      type: 'complete',
    })

    await vi.advanceTimersByTimeAsync(50)

    const messages = useChatStore.getState().messages['chat-1'] ?? []
    const working = messages.find((m) => m.role === 'working') as WorkingChatMessage
    // Because it was superseded by msg-2, isPaused must be false
    expect(working.isPaused).toBe(false)
  })

  it('continuePlanning sends chat.continue via WebSocket RPC and unpauses working block', () => {
    setupConnected()
    const sendSpy = vi.spyOn(useWebSocketStore.getState(), 'send')

    // Set up a paused working block
    useChatStore.getState().handlePlanningStatusResult({
      chatId: 'chat-1',
      reason: 'max_iterations',
      status: 'paused',
      type: 'planning_status',
    })

    const paused = useChatStore
      .getState()
      .messages['chat-1'].find((m) => m.role === 'working') as WorkingChatMessage
    expect(paused.isPaused).toBe(true)

    useChatStore.getState().continuePlanning('chat-1')

    const resumed = useChatStore
      .getState()
      .messages['chat-1'].find((m) => m.role === 'working') as WorkingChatMessage
    expect(resumed.isPaused).toBe(false)
    expect(resumed.isStreaming).toBe(true)
    expect(useChatStore.getState().sendingChatIds.has('chat-1')).toBe(true)

    expect(sendSpy).toHaveBeenCalledWith('chat.continue', {
      chatId: 'chat-1',
      modelName: 'model-1',
      providerId: 'provider-1',
      teamId: 'team-1',
    })
  })
})
