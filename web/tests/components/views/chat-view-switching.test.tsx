import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Mutable store state that tests can modify between renders
let mockCurrentChatId: null | string = null
let mockMessages: Record<string, Array<{ content: string; id: string; role: 'assistant' | 'user'; timestamp: Date }>> = {}
let mockIsConnected = true

const mockSubscribeChat = vi.fn()
const mockUnsubscribeChat = vi.fn()

vi.mock('@/store/ui-store', () => ({
  useUIStore: vi.fn((selector?: (state: { sidebarOpen: boolean }) => unknown) => {
    const state = {
      sidebarOpen: true,
    }
    return selector ? selector(state) : state
  }),
}))

vi.mock('@/store/websocket-store', () => ({
  useWebSocketStore: vi.fn((selector) => {
    const state = {
      isConnected: mockIsConnected,
    }
    return selector ? selector(state) : state
  }),
}))

vi.mock('@/store/chat-store', () => ({
  useChatStore: vi.fn((selector) => {
    const state = {
      addMessage: vi.fn(),
      currentChatId: mockCurrentChatId,
      messages: mockMessages,
      sendMessage: vi.fn(),
      subscribeChat: mockSubscribeChat,
      unsubscribeChat: mockUnsubscribeChat,
    }
    return selector ? selector(state) : state
  }),
}))

vi.mock('@/store/canvas-store', () => ({
  useCanvasStore: vi.fn((selector) => {
    const state = {
      canvases: {},
      selectChat: vi.fn(),
    }
    return selector ? selector(state) : state
  }),
}))

vi.mock('@/store/auth-store', () => ({
  useAuthStore: Object.assign(
    vi.fn(() => ({
      currentTeamId: null,
    })),
    {
      getState: vi.fn(() => ({
        currentTeamId: null,
      })),
    }
  ),
}))

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')
  return {
    ...actual,
    Outlet: () => null,
    useNavigate: vi.fn(() => vi.fn()),
    useParams: vi.fn(() => ({ id: mockCurrentChatId })),
    useRouterState: vi.fn((opts) =>
      opts.select({ location: { pathname: '/chats/session-1' } }),
    ),
    useSearch: vi.fn(() => ({ tab: undefined })),
  }
})

// Import after mocks
import { ChatView } from '@/components/views/chat-view'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
  },
})

function renderWithProviders(ui: React.ReactElement) {
  return render(ui, { wrapper: Wrapper })
}

function Wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('ChatView - Chat Switching', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCurrentChatId = null
    mockMessages = {}
    mockIsConnected = true
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows "No chat selected" when no session is selected', () => {
    renderWithProviders(<ChatView />)

    expect(screen.getByText('No chat selected')).toBeTruthy()
  })

  it('calls subscribeChat when a session is selected with no messages', () => {
    mockCurrentChatId = 'session-a'
    mockMessages = {}

    renderWithProviders(<ChatView />)

    expect(mockSubscribeChat).toHaveBeenCalledWith('session-a')
  })

  it('calls subscribeChat when switching to a different session', () => {
    // First render with session A
    mockCurrentChatId = 'session-a'
    mockMessages = {}

    const { rerender } = renderWithProviders(<ChatView />)

    // Verify subscribeChat was called for session A
    expect(mockSubscribeChat).toHaveBeenCalledWith('session-a')
    mockSubscribeChat.mockClear()

    // Now simulate switching to session B (messages still empty)
    mockCurrentChatId = 'session-b'
    mockMessages = {}

    rerender(<ChatView />)

    // Verify subscribeChat was called for session B
    expect(mockSubscribeChat).toHaveBeenCalledWith('session-b')
  })

  it('does not call loadSessionMessages when session already has messages', () => {
    const existingMessages = {
      'session-with-messages': [
        {
          content: 'Existing message',
          id: 'msg-1',
          role: 'user' as const,
          timestamp: new Date(),
        },
      ],
    }

    mockCurrentChatId = 'session-with-messages'
    mockMessages = existingMessages

    renderWithProviders(<ChatView />)

    // Subscribes to chat on mount
    expect(mockSubscribeChat).toHaveBeenCalledWith('session-with-messages')

    // Verify existing message is displayed
    expect(screen.getByText('Existing message')).toBeTruthy()
  })

  it('displays messages from the selected session', async () => {
    const sessionMessages = [
      {
        content: 'Hello from this session',
        id: 'msg-1',
        role: 'user' as const,
        timestamp: new Date(),
      },
      {
        content: 'Response from assistant',
        id: 'msg-2',
        role: 'assistant' as const,
        timestamp: new Date(),
      },
    ]

    mockCurrentChatId = 'session-with-messages'
    mockMessages = {
      'session-with-messages': sessionMessages,
    }

    renderWithProviders(<ChatView />)

    // Verify messages are displayed
    await waitFor(() => {
      expect(screen.getByText('Hello from this session')).toBeTruthy()
      expect(screen.getByText('Response from assistant')).toBeTruthy()
    })
  })

  it('subscribes to multiple sessions when switching between them', () => {
    // Start with session A
    mockCurrentChatId = 'session-a'
    mockMessages = {}

    const { rerender } = renderWithProviders(<ChatView />)

    expect(mockSubscribeChat).toHaveBeenCalledWith('session-a')
    mockSubscribeChat.mockClear()

    // Switch to session B
    mockCurrentChatId = 'session-b'
    mockMessages = {}

    rerender(<ChatView />)

    expect(mockSubscribeChat).toHaveBeenCalledWith('session-b')
    mockSubscribeChat.mockClear()

    // Switch to session C
    mockCurrentChatId = 'session-c'
    mockMessages = {}

    rerender(<ChatView />)

    expect(mockSubscribeChat).toHaveBeenCalledWith('session-c')
  })
})
