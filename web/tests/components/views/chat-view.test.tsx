import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ChatView } from '@/components/views/chat-view'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'

// Mock dependencies
vi.mock('@/store/ui-store', () => ({
  useUIStore: Object.assign(
    vi.fn(() => ({
      sidebarOpen: true,
    })),
    {
      getState: vi.fn(() => ({
        selectedModelName: 'gpt-4o',
        selectedProviderId: 'provider-1',
        sidebarOpen: true,
      })),
    },
  ),
}))

vi.mock('@/store/websocket-store', () => {
  const mockSend = vi.fn()
  const mockStore = Object.assign(
    vi.fn((selector) => {
      const state = {
        isConnected: true,
        send: mockSend,
      }
      return selector ? selector(state) : state
    }),
    {
      getState: vi.fn(() => ({
        isConnected: true,
        send: mockSend,
      })),
    }
  )
  return { useWebSocketStore: mockStore }
})

vi.mock('@/store/auth-store', () => ({
  useAuthStore: Object.assign(
    vi.fn(() => ({
      currentTeamId: 'team-1',
    })),
    {
      getState: vi.fn(() => ({
        currentTeamId: 'team-1',
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
    useParams: vi.fn(() => ({ executionId: 'exec-1', id: 'session-1' })),
    useRouterState: vi.fn((opts) =>
      opts.select({ location: { pathname: '/chats/session-1' } }),
    ),
    useSearch: vi.fn(() => ({ tab: undefined })),
  }
})

vi.mock('@/lib/execution-api', () => ({
  listChatExecutions: vi.fn(() => Promise.resolve([])),
}))

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

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

describe('ChatView', () => {
  beforeEach(() => {
    vi.mocked(useParams).mockReturnValue({ executionId: 'exec-1', id: 'session-1' })
    useChatStore.setState({
      currentChatId: 'session-1',
      messages: {
        'session-1': [
          { content: 'Hello', id: 'msg-1', role: 'user', timestamp: new Date() },
          { content: 'Hi there', id: 'msg-2', role: 'assistant', timestamp: new Date() },
        ],
      },
    })
    useCanvasStore.setState({
      canvases: {},
    })
    useExecutionStore.setState({
      logs: {},
      replayingExecutionId: null,
      terminalFullscreen: false,
      terminalHeight: 256,
      terminalOpen: false,
    })
    queryClient.clear()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders without crashing when no session selected', async () => {
    useChatStore.setState({ currentChatId: null })
    const router = await import('@tanstack/react-router')
    vi.mocked(router.useParams).mockReturnValue({ id: null })
    renderWithProviders(<ChatView />)
    expect(screen.getByText('No chat selected')).toBeTruthy()
  })

  it('renders messages for the current session', () => {
    renderWithProviders(<ChatView />)
    expect(screen.getByText('Hello')).toBeTruthy()
    expect(screen.getByText('Hi there')).toBeTruthy()
  })

  it('toggles terminal open when terminal state is true', () => {
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] Test log'] },
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)
    expect(screen.getByText('Terminal - Sandbox Console')).toBeTruthy()
    expect(screen.getByText('[System] Test log')).toBeTruthy()
  })

  it('closes terminal when Close button is clicked', async () => {
    const user = userEvent.setup()
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] Test log'] },
      terminalFullscreen: true,
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)

    await user.click(screen.getByRole('button', { name: /close/i }))
    expect(useExecutionStore.getState().terminalOpen).toBe(false)
    expect(useExecutionStore.getState().terminalFullscreen).toBe(false)
  })

  it('expands the terminal to fullscreen when the expand button is clicked', async () => {
    const user = userEvent.setup()
    useExecutionStore.setState({ logs: { 'exec-1': ['[System] Test log'] }, terminalOpen: true })
    renderWithProviders(<ChatView />)

    await user.click(screen.getByRole('button', { name: /expand terminal/i }))

    expect(useExecutionStore.getState().terminalFullscreen).toBe(true)
    expect(screen.getByTestId('terminal-panel')).toHaveClass('fixed')
  })

  it('exits fullscreen when the minimize button is clicked', async () => {
    const user = userEvent.setup()
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] Test log'] },
      terminalFullscreen: true,
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)

    await user.click(screen.getByRole('button', { name: /exit fullscreen/i }))

    expect(useExecutionStore.getState().terminalFullscreen).toBe(false)
    expect(screen.getByTestId('terminal-panel')).not.toHaveClass('fixed')
  })

  it('resizes the terminal by dragging the resize handle', async () => {
    const user = userEvent.setup()
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] Test log'] },
      terminalHeight: 256,
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)

    const handle = screen.getByRole('button', { name: /resize terminal/i })
    await user.pointer({ keys: '[MouseLeft>]', target: handle })
    await user.pointer({ coords: { y: -200 } })
    await user.pointer({ keys: '[/MouseLeft]' })

    expect(useExecutionStore.getState().terminalHeight).toBe(456)
  })

  it('clamps the resized terminal height to the minimum', async () => {
    const user = userEvent.setup()
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] Test log'] },
      terminalHeight: 256,
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)

    const handle = screen.getByRole('button', { name: /resize terminal/i })
    await user.pointer({ keys: '[MouseLeft>]', target: handle })
    await user.pointer({ coords: { y: 5000 } })
    await user.pointer({ keys: '[/MouseLeft]' })

    expect(useExecutionStore.getState().terminalHeight).toBe(128)
  })

  it('does not render the resize handle when the terminal is fullscreen', () => {
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] Test log'] },
      terminalFullscreen: true,
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)

    expect(screen.queryByRole('button', { name: /resize terminal/i })).toBeNull()
  })

  it('displays different log severities with correct styling', () => {
    useExecutionStore.setState({
      logs: {
        'exec-1': [
          '[System] System log',
          '[Warning] Warning log',
          '[Error] Error log',
          '[Output] Output log',
          'Regular log',
        ],
      },
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)

    expect(screen.getByText('[System] System log')).toHaveClass('text-blue-400')
    expect(screen.getByText('[Warning] Warning log')).toHaveClass('text-yellow-500')
    expect(screen.getByText('[Error] Error log')).toHaveClass('text-red-400')
    expect(screen.getByText('[Output] Output log')).toHaveClass('text-emerald-400')
    expect(screen.getByText('Regular log')).toHaveClass('text-zinc-300')
  })

  it('auto-scrolls the terminal with instant scrolling when a log is appended', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
    useExecutionStore.setState({
      logs: { 'exec-1': ['[System] first'] },
      terminalOpen: true,
    })
    renderWithProviders(<ChatView />)
    scrollSpy.mockClear()

    useExecutionStore.getState().addLog('exec-1', '[System] second')

    await waitFor(() => {
      expect(scrollSpy).toHaveBeenCalledWith(
        expect.objectContaining({ behavior: 'auto', block: 'end' }),
      )
    })
    scrollSpy.mockRestore()
  })

  describe('auto-scroll behavior', () => {
    function getScrollContainer() {
      // The scrollable message list inside ChatView's design-stage chat pane.
      const container = document.querySelector('[data-testid="design-chat-history"]')
      if (!container) throw new Error('scroll container not found')
      return container as HTMLDivElement
    }

    function setScrollMetrics(
      container: HTMLDivElement,
      { clientHeight, scrollHeight, scrollTop }: { clientHeight: number; scrollHeight: number; scrollTop: number },
    ) {
      Object.defineProperty(container, 'clientHeight', { configurable: true, value: clientHeight })
      Object.defineProperty(container, 'scrollHeight', { configurable: true, value: scrollHeight })
      Object.defineProperty(container, 'scrollTop', { configurable: true, value: scrollTop, writable: true })
    }

    it('auto-scrolls to the bottom when new messages arrive while already near the bottom', async () => {
      const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
      renderWithProviders(<ChatView />)
      scrollSpy.mockClear()

      useChatStore.setState((state) => ({
        messages: {
          ...state.messages,
          'session-1': [
            ...(state.messages['session-1'] ?? []),
            { content: 'New message', id: 'msg-3', role: 'assistant', timestamp: new Date() },
          ],
        },
      }))

      await waitFor(() => {
        expect(scrollSpy).toHaveBeenCalledWith(
          expect.objectContaining({ behavior: 'auto', block: 'end' }),
        )
      })
      scrollSpy.mockRestore()
    })

    it('does not force-scroll when the user has scrolled up to read earlier messages', () => {
      const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
      renderWithProviders(<ChatView />)
      const container = getScrollContainer()

      // Simulate the user scrolling far away from the bottom.
      setScrollMetrics(container, { clientHeight: 400, scrollHeight: 2000, scrollTop: 0 })
      container.dispatchEvent(new Event('scroll'))
      scrollSpy.mockClear()

      useChatStore.setState((state) => ({
        messages: {
          ...state.messages,
          'session-1': [
            ...(state.messages['session-1'] ?? []),
            { content: 'New message while scrolled up', id: 'msg-3', role: 'assistant', timestamp: new Date() },
          ],
        },
      }))

      expect(scrollSpy).not.toHaveBeenCalled()
      scrollSpy.mockRestore()
    })

    it('force-scrolls to the bottom when the user sends a new message, even if scrolled away', async () => {
      const user = userEvent.setup()
      const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
      renderWithProviders(<ChatView />)
      const container = getScrollContainer()

      setScrollMetrics(container, { clientHeight: 400, scrollHeight: 2000, scrollTop: 0 })
      container.dispatchEvent(new Event('scroll'))
      scrollSpy.mockClear()

      const input = screen.getByPlaceholderText(/type a message/i)
      await user.type(input, 'Hello again')
      await user.keyboard('{Enter}')

      await waitFor(() => {
        expect(scrollSpy).toHaveBeenCalledWith(
          expect.objectContaining({ behavior: 'auto', block: 'end' }),
        )
      })
      scrollSpy.mockRestore()
    })
  })

  it('renders running execution in the stage nav without forcefully opening terminal', async () => {
    const { listChatExecutions } = await import('@/lib/execution-api')
    vi.mocked(listChatExecutions).mockResolvedValue([
      {
        chatId: 'session-1',
        completedAt: null,
        exitCode: null,
        id: 'exec-1',
        startedAt: '2026-01-01T00:00:00.000Z',
        status: 'RUNNING',
      },
    ])

    renderWithProviders(<ChatView />)

    await waitFor(() => {
      expect(screen.getByTestId('stage-card-run-exec-1')).toBeInTheDocument()
    })
    expect(useExecutionStore.getState().terminalOpen).toBe(false)
  })
})
