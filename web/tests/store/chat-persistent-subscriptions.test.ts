import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useChatStore } from '@/store/chat-store'
import { useWebSocketStore } from '@/store/websocket-store'

vi.mock('@/store/websocket-store', () => ({
  useWebSocketStore: {
    getState: vi.fn(() => ({
      send: vi.fn(),
    })),
  },
}))

vi.mock('@/store/auth-store', () => ({
  useAuthStore: {
    getState: vi.fn(() => ({
      currentTeamId: 'team-123',
    })),
  },
}))

describe('chatStore - Persistent Subscriptions', () => {
  beforeEach(() => {
    useChatStore.getState().clearChats()
    vi.clearAllMocks()
  })

  it('sends chat.subscribe RPC on initial subscription', () => {
    const mockSend = vi.fn()
    vi.mocked(useWebSocketStore.getState).mockReturnValue({ send: mockSend } as unknown as ReturnType<typeof useWebSocketStore.getState>)

    useChatStore.getState().subscribeChat('chat-1')

    expect(useChatStore.getState().subscribedChatIds.has('chat-1')).toBe(true)
    expect(useChatStore.getState().currentChatId).toBe('chat-1')
    expect(mockSend).toHaveBeenCalledWith('chat.subscribe', { chatId: 'chat-1', teamId: 'team-123' })
  })

  it('does not resend chat.subscribe RPC if chat is already subscribed', () => {
    const mockSend = vi.fn()
    vi.mocked(useWebSocketStore.getState).mockReturnValue({ send: mockSend } as unknown as ReturnType<typeof useWebSocketStore.getState>)

    // First subscription
    useChatStore.getState().subscribeChat('chat-1')
    expect(mockSend).toHaveBeenCalledTimes(1)
    mockSend.mockClear()

    // Second subscription call to same chat (e.g. view re-render or re-visit)
    useChatStore.getState().subscribeChat('chat-1')

    expect(useChatStore.getState().subscribedChatIds.has('chat-1')).toBe(true)
    expect(useChatStore.getState().currentChatId).toBe('chat-1')
    expect(mockSend).not.toHaveBeenCalled()
  })

  it('maintains multiple subscriptions in subscribedChatIds when switching chats', () => {
    const mockSend = vi.fn()
    vi.mocked(useWebSocketStore.getState).mockReturnValue({ send: mockSend } as unknown as ReturnType<typeof useWebSocketStore.getState>)

    useChatStore.getState().subscribeChat('chat-1')
    useChatStore.getState().subscribeChat('chat-2')

    const state = useChatStore.getState()
    expect(state.subscribedChatIds.has('chat-1')).toBe(true)
    expect(state.subscribedChatIds.has('chat-2')).toBe(true)
    expect(state.currentChatId).toBe('chat-2')
    expect(mockSend).toHaveBeenCalledWith('chat.subscribe', { chatId: 'chat-1', teamId: 'team-123' })
    expect(mockSend).toHaveBeenCalledWith('chat.subscribe', { chatId: 'chat-2', teamId: 'team-123' })

    // Switching back to chat-1 should be a NO-OP over network
    mockSend.mockClear()
    useChatStore.getState().subscribeChat('chat-1')
    expect(useChatStore.getState().currentChatId).toBe('chat-1')
    expect(mockSend).not.toHaveBeenCalled()
  })

  it('removes chat from subscribedChatIds and sends chat.unsubscribe on explicit unsubscribe', () => {
    const mockSend = vi.fn()
    vi.mocked(useWebSocketStore.getState).mockReturnValue({ send: mockSend } as unknown as ReturnType<typeof useWebSocketStore.getState>)

    useChatStore.getState().subscribeChat('chat-1')
    useChatStore.getState().unsubscribeChat('chat-1')

    expect(useChatStore.getState().subscribedChatIds.has('chat-1')).toBe(false)
    expect(useChatStore.getState().currentChatId).toBeNull()
    expect(mockSend).toHaveBeenCalledWith('chat.unsubscribe', { chatId: 'chat-1' })
  })
})
