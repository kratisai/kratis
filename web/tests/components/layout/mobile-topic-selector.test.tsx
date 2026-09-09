import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MobileTopicSelector } from '@/components/layout/mobile-topic-selector'
import * as executionApi from '@/lib/execution-api'
import { useCanvasStore } from '@/store/canvas-store'

const mockNavigate = vi.fn()
let mockParams = { docId: undefined as string | undefined, executionId: undefined as string | undefined, id: 'chat-1' as string | undefined }
let mockSearch = { tab: undefined as string | undefined }

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => mockParams,
  useSearch: () => mockSearch,
}))

describe('MobileTopicSelector', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    mockParams = { docId: undefined, executionId: undefined, id: 'chat-1' }
    mockSearch = { tab: undefined }
    useCanvasStore.setState({
      canvases: {
        'chat-1': [
          {
            canvasType: 'SPEC',
            chatId: 'chat-1',
            content: '# Plan',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'Plan.md',
            version: 1,
          },
        ],
      },
    })
    vi.spyOn(executionApi, 'listChatExecutions').mockResolvedValue([
      {
        chatId: 'chat-1',
        harness: 'claude-code',
        id: 'exec-1',
        startedAt: '2026-08-30T10:00:00Z',
        status: 'RUNNING',
      },
    ])
  })

  it('renders nothing when not on a chat route', () => {
    mockParams = { docId: undefined, executionId: undefined, id: undefined }
    render(
      <QueryClientProvider client={queryClient}>
        <MobileTopicSelector />
      </QueryClientProvider>,
    )

    expect(screen.queryByTestId('mobile-topic-selector')).not.toBeInTheDocument()
  })

  it('renders Design & Plan when on chat root route and opens dropdown', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <MobileTopicSelector />
      </QueryClientProvider>,
    )

    expect(screen.getByText('Design & Plan')).toBeInTheDocument()

    const trigger = screen.getByRole('button', { name: /topic navigation menu/i })
    fireEvent.pointerDown(trigger, { pointerType: 'mouse' })

    expect(await screen.findByText('Chat Conversation')).toBeInTheDocument()
    expect(screen.getByText('Plan.md')).toBeInTheDocument()
    expect(await screen.findByText('Run 1')).toBeInTheDocument()
    expect(screen.getByText('Activity Log')).toBeInTheDocument()
    expect(screen.getByText('Changes')).toBeInTheDocument()
  })

  it('navigates to canvas document when clicked in dropdown', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <MobileTopicSelector />
      </QueryClientProvider>,
    )

    const trigger = screen.getByRole('button', { name: /topic navigation menu/i })
    fireEvent.pointerDown(trigger, { pointerType: 'mouse' })

    const docItem = await screen.findByText('Plan.md')
    fireEvent.click(docItem)

    expect(mockNavigate).toHaveBeenCalledWith({
      params: { docId: 'doc-1', id: 'chat-1' },
      to: '/chats/$id/canvas/$docId',
    })
  })

  it('displays active doc title when docId is in route', () => {
    mockParams = { docId: 'doc-1', executionId: undefined, id: 'chat-1' }

    render(
      <QueryClientProvider client={queryClient}>
        <MobileTopicSelector />
      </QueryClientProvider>,
    )

    expect(screen.getByText('Plan.md')).toBeInTheDocument()
  })

  it('displays Run 1 · Changes when viewing changes tab of execution', async () => {
    mockParams = { docId: undefined, executionId: 'exec-1', id: 'chat-1' }
    mockSearch = { tab: 'changes' }

    render(
      <QueryClientProvider client={queryClient}>
        <MobileTopicSelector />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('Run 1 · Changes')).toBeInTheDocument()
  })

  it('navigates to execution activity log from dropdown', async () => {
    render(
      <QueryClientProvider client={queryClient}>
        <MobileTopicSelector />
      </QueryClientProvider>,
    )

    const trigger = screen.getByRole('button', { name: /topic navigation menu/i })
    fireEvent.pointerDown(trigger, { pointerType: 'mouse' })

    const logItem = await screen.findByText('Activity Log')
    fireEvent.click(logItem)

    expect(mockNavigate).toHaveBeenCalledWith({
      params: { executionId: 'exec-1', id: 'chat-1' },
      search: {},
      to: '/chats/$id/executions/$executionId',
    })
  })
})
