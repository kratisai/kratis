import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { act } from 'react'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ChatMessage } from '@/store/chat-store'
import type { CanvasDocument } from '@/types/canvas-types'

import { PLAN_REQUEST_MESSAGE } from '@/components/chat/plan-shortcut'
import { DesignStageView } from '@/components/topic/design-stage-view'
import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'
import { useWebSocketStore } from '@/store/websocket-store'

let mockParams = { docId: null as null | string, id: 'chat-1' }

vi.mock('@tanstack/react-router', () => ({
  Outlet: () => <div data-testid="outlet-mock">Canvas Outlet</div>,
  useNavigate: () => vi.fn(),
  useParams: () => mockParams,
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn() },
}))

function agentRespondedMessage(): ChatMessage {
  return {
    content: 'Here is what we could build.',
    id: 'm2',
    role: 'assistant',
    timestamp: new Date(),
  }
}

function canvasDoc(overrides: Partial<CanvasDocument> = {}): CanvasDocument {
  return {
    canvasType: 'SPEC',
    chatId: 'chat-1',
    content: '# Plan',
    documentId: 'doc-1',
    isNewRepo: false,
    title: 'Plan.md',
    version: 1,
    ...overrides,
  }
}

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
}

const originalInnerWidth = window.innerWidth

describe('DesignStageView', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    mockParams = { docId: null, id: 'chat-1' }
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    useWebSocketStore.setState({ isConnected: true })
    useCanvasStore.setState({ canvases: {}, unreadCanvasDocIds: {} })
    useChatStore.setState({
      messages: {
        'chat-1': [
          {
            content: 'Let us build auth refresh flow',
            id: 'm1',
            role: 'user',
            timestamp: new Date(),
          },
        ],
      },
      sendingChatIds: new Set<string>(),
    })
  })

  afterEach(() => {
    setViewportWidth(originalInnerWidth)
  })

  it('renders chat message in single column when no canvases exist', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )

    expect(screen.getByText('Let us build auth refresh flow')).toBeInTheDocument()
    expect(screen.queryByTestId('design-canvas-pane')).not.toBeInTheDocument()
  })

  it('renders split pane with outlet when canvas documents exist', () => {
    useCanvasStore.setState({
      canvases: {
        'chat-1': [canvasDoc()],
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )

    expect(screen.getByText('Let us build auth refresh flow')).toBeInTheDocument()
    expect(screen.getByTestId('design-canvas-pane')).toBeInTheDocument()
    expect(screen.getByText('Plan.md')).toBeInTheDocument()
  })

  it('restricts the side-by-side split to widescreen via responsive classes', () => {
    useCanvasStore.setState({
      canvases: {
        'chat-1': [canvasDoc()],
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )

    // Chat pane is capped at 45% only from the xl breakpoint up and is never
    // hidden while the chat route is active (no docId).
    const chatPane = screen.getByTestId('design-chat-pane')
    expect(chatPane).toHaveClass('xl:max-w-[45%]')
    expect(chatPane).not.toHaveClass('hidden')

    // Canvas pane only participates in the split from the xl breakpoint up;
    // below that it is hidden (the URL selects a single content panel instead).
    const canvasPane = screen.getByTestId('design-canvas-pane')
    expect(canvasPane).toHaveClass('hidden')
    expect(canvasPane).toHaveClass('xl:flex')
  })

  it('lets chat content flow into the mobile document and keeps the composer sticky', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )

    const stage = screen.getByTestId('design-stage-view')
    const history = screen.getByTestId('design-chat-history')
    const composer = screen.getByTestId('design-chat-composer')

    expect(stage).not.toHaveClass('overflow-hidden')
    expect(stage).toHaveClass('md:overflow-hidden', 'min-w-0')
    expect(history).toHaveClass('overflow-visible', 'md:overflow-auto')
    expect(history).not.toHaveClass('overflow-auto')
    expect(composer).toHaveClass('sticky', 'bottom-0', 'z-10')
  })

  it('contains wide message bodies with overflow-x-auto so they cannot widen the page', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )

    const bubble = screen.getByText('Let us build auth refresh flow')
    expect(bubble.closest('[data-slot="card"]')).toHaveClass('overflow-x-auto')
  })

  it('autoscrolls to new messages on desktop only when the history pane is near the bottom', async () => {
    setViewportWidth(1024)
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView')
    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )
    const history = screen.getByTestId('design-chat-history')

    Object.defineProperty(history, 'scrollHeight', { configurable: true, value: 1000 })
    Object.defineProperty(history, 'clientHeight', { configurable: true, value: 500 })

    history.scrollTop = 200
    fireEvent.scroll(history)
    scrollIntoView.mockClear()
    await act(async () => {
      useChatStore.getState().addMessage('chat-1', {
        content: 'far from bottom',
        id: 'far-from-bottom',
        role: 'user',
        timestamp: new Date(),
      })
    })
    expect(scrollIntoView).not.toHaveBeenCalled()

    history.scrollTop = 400
    fireEvent.scroll(history)
    await act(async () => {
      useChatStore.getState().addMessage('chat-1', {
        content: 'near bottom',
        id: 'near-bottom',
        role: 'user',
        timestamp: new Date(),
      })
    })
    expect(scrollIntoView).toHaveBeenCalled()
  })

  it('tracks the window scroll instead of the pane on mobile', async () => {
    setViewportWidth(375)
    Object.defineProperty(document.documentElement, 'scrollHeight', {
      configurable: true,
      value: 2000,
    })
    const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView')
    render(
      <QueryClientProvider client={queryClient}>
        <DesignStageView chatId="chat-1" />
      </QueryClientProvider>,
    )

    Object.defineProperty(window, 'scrollY', { configurable: true, value: 1000 })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 })
    await act(async () => {
      window.dispatchEvent(new Event('scroll'))
    })
    scrollIntoView.mockClear()
    await act(async () => {
      useChatStore.getState().addMessage('chat-1', {
        content: 'mobile far',
        id: 'mobile-far-from-bottom',
        role: 'user',
        timestamp: new Date(),
      })
    })
    expect(scrollIntoView).not.toHaveBeenCalled()

    Object.defineProperty(window, 'scrollY', { configurable: true, value: 1500 })
    await act(async () => {
      window.dispatchEvent(new Event('scroll'))
    })
    await act(async () => {
      useChatStore.getState().addMessage('chat-1', {
        content: 'mobile near',
        id: 'mobile-near-bottom',
        role: 'user',
        timestamp: new Date(),
      })
    })
    expect(scrollIntoView).toHaveBeenCalled()
  })

  describe('copy button on agent messages', () => {
    beforeEach(() => {
      useChatStore.setState({
        messages: {
          'chat-1': [
            {
              content: 'Let us build auth refresh flow',
              id: 'm1',
              role: 'user',
              timestamp: new Date(),
            },
            {
              content: '# Plan\n\n- step one\n- step two',
              id: 'm2',
              role: 'assistant',
              timestamp: new Date(),
            },
          ],
        },
      })
    })

    it('does not render a copy button for the user message', () => {
      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      const userBubble = screen
        .getByText('Let us build auth refresh flow')
        .closest('[data-slot="card"]')
      expect(
        userBubble
          ? within(userBubble as HTMLElement).queryByRole('button', { name: /copy message/i })
          : null,
      ).not.toBeInTheDocument()
    })

    it('renders a hidden-until-hover copy button for the agent message', () => {
      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      const copyButton = screen.getByRole('button', { name: /copy message/i })
      expect(copyButton).toHaveClass('opacity-0')
      expect(copyButton).toHaveClass('group-hover:opacity-100')
    })

    it('copies the raw markdown content to the clipboard and shows a success toast when clicked', async () => {
      const user = userEvent.setup()
      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      const copyButton = screen.getByRole('button', { name: /copy message/i })
      await user.click(copyButton)

      expect(await navigator.clipboard.readText()).toBe('# Plan\n\n- step one\n- step two')
      expect(toast.success).toHaveBeenCalledWith('Copied to clipboard')
    })

    it('briefly swaps the copy icon for a check icon after copying', async () => {
      const user = userEvent.setup()
      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      const copyButton = screen.getByRole('button', { name: /copy message/i })
      expect(copyButton.querySelector('.lucide-copy')).toBeInTheDocument()

      await user.click(copyButton)

      await waitFor(() => {
        expect(copyButton.querySelector('.lucide-check')).toBeInTheDocument()
      })
    })
  })

  describe('canvas activity synchronization', () => {
    it('clears canvas activity for active docId', () => {
      mockParams = { docId: 'doc-1', id: 'chat-1' }
      useCanvasStore.setState({
        canvases: {
          'chat-1': [canvasDoc()],
        },
        unreadCanvasDocIds: {
          'chat-1': ['doc-1'],
        },
      })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(useCanvasStore.getState().unreadCanvasDocIds['chat-1']).toEqual([])
    })

    it('clears canvas activity for first document on widescreen dual-pane when docId is not set', () => {
      setViewportWidth(1440)
      window.dispatchEvent(new Event('resize'))
      mockParams = { docId: null, id: 'chat-1' }
      useCanvasStore.setState({
        canvases: {
          'chat-1': [canvasDoc({ documentId: 'doc-first' })],
        },
        unreadCanvasDocIds: {
          'chat-1': ['doc-first'],
        },
      })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(useCanvasStore.getState().unreadCanvasDocIds['chat-1']).toEqual([])
    })

    it('does not clear canvas activity when not on widescreen and docId is not set', () => {
      setViewportWidth(768)
      window.dispatchEvent(new Event('resize'))
      mockParams = { docId: null, id: 'chat-1' }
      useCanvasStore.setState({
        canvases: {
          'chat-1': [canvasDoc({ documentId: 'doc-first' })],
        },
        unreadCanvasDocIds: {
          'chat-1': ['doc-first'],
        },
      })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(useCanvasStore.getState().unreadCanvasDocIds['chat-1']).toEqual(['doc-first'])
    })
  })

  describe('implementation plan shortcut', () => {
    function setLastMessage(message: ChatMessage) {
      useChatStore.setState({
        messages: {
          'chat-1': [
            {
              content: 'Let us build auth refresh flow',
              id: 'm1',
              role: 'user',
              timestamp: new Date(),
            },
            message,
          ],
        },
      })
    }

    it('renders the shortcut after the agent completed a response', () => {
      setLastMessage(agentRespondedMessage())

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      const shortcut = screen.getByTestId('plan-shortcut')
      expect(
        within(shortcut).getByRole('button', { name: /implementation plan/i }),
      ).toBeInTheDocument()
    })

    it('is hidden while the agent has not responded yet', () => {
      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(screen.queryByTestId('plan-shortcut')).not.toBeInTheDocument()
    })

    it('is hidden while the agent is streaming a response', () => {
      useChatStore.setState({
        messages: {
          'chat-1': [
            {
              content: 'Let us build auth refresh flow',
              id: 'm1',
              role: 'user',
              timestamp: new Date(),
            },
            {
              endTime: null,
              id: 'working-1',
              isStreaming: true,
              items: [],
              role: 'working',
              startTime: Date.now(),
              timestamp: new Date(),
            },
          ],
        },
      })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(screen.queryByTestId('plan-shortcut')).not.toBeInTheDocument()
    })

    it('is hidden while a chat send is still in flight', () => {
      setLastMessage(agentRespondedMessage())
      useChatStore.setState({ sendingChatIds: new Set(['chat-1']) })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(screen.queryByTestId('plan-shortcut')).not.toBeInTheDocument()
    })

    it('is hidden when the chat contains an executable plan canvas', () => {
      setLastMessage(agentRespondedMessage())
      useCanvasStore.setState({ canvases: { 'chat-1': [canvasDoc({ canvasType: 'SPEC' })] } })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(screen.queryByTestId('plan-shortcut')).not.toBeInTheDocument()
    })

    it('is visible when the chat only contains a non-executable canvas', () => {
      setLastMessage(agentRespondedMessage())
      useCanvasStore.setState({ canvases: { 'chat-1': [canvasDoc({ canvasType: 'DOCUMENT' })] } })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(screen.getByTestId('plan-shortcut')).toBeInTheDocument()
    })

    it('is hidden when the last response is an error', () => {
      setLastMessage({
        content: 'Something went wrong.',
        id: 'm2',
        isError: true,
        role: 'assistant',
        timestamp: new Date(),
      })

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      expect(screen.queryByTestId('plan-shortcut')).not.toBeInTheDocument()
    })

    it('sends the plan request message to the agent when clicked', async () => {
      const user = userEvent.setup()
      const sendSpy = vi.fn()
      useWebSocketStore.setState({ send: sendSpy })
      useAuthStore.setState({ currentTeamId: 'team-1' })
      useUIStore.getState().setSelectedModel('provider-1', 'model-1')
      setLastMessage(agentRespondedMessage())

      render(
        <QueryClientProvider client={queryClient}>
          <DesignStageView chatId="chat-1" />
        </QueryClientProvider>,
      )

      await user.click(screen.getByRole('button', { name: /implementation plan/i }))

      expect(sendSpy).toHaveBeenCalledWith(
        'chat.send',
        expect.objectContaining({
          chatId: 'chat-1',
          message: PLAN_REQUEST_MESSAGE,
        }),
      )
      expect(screen.getByText(PLAN_REQUEST_MESSAGE)).toBeInTheDocument()
      // The optimistic user message hides the shortcut again.
      expect(screen.queryByTestId('plan-shortcut')).not.toBeInTheDocument()
    })
  })
})
