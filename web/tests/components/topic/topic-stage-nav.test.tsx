import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SandboxExecutionDto } from '@/lib/execution-api'
import type { CanvasDocument } from '@/types/canvas-types'

import { TopicStageNav } from '@/components/topic/topic-stage-nav'
import { useCanvasStore } from '@/store/canvas-store'

const mockNavigate = vi.fn()
let mockSearch: { tab?: string } = { tab: undefined }
const mockUseDiffSummary = vi.fn<() => { data?: { files: unknown[] } }>(() => ({ data: undefined }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useSearch: () => mockSearch,
}))

vi.mock('@/hooks/use-diff', () => ({
  useDiffSummary: () => mockUseDiffSummary(),
}))

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

describe('TopicStageNav', () => {
  const mockExecutions: SandboxExecutionDto[] = [
    {
      chatId: 'chat-1',
      completedAt: new Date(Date.now() - 30000).toISOString(),
      harness: 'PI',
      id: 'exec-1',
      startedAt: new Date(Date.now() - 60000).toISOString(),
      status: 'COMPLETED',
      totalSpend: 0.1234,
    },
    {
      chatId: 'chat-1',
      harness: 'PI',
      id: 'exec-2',
      startedAt: new Date(Date.now() - 20000).toISOString(),
      status: 'RUNNING',
      totalSpend: 1.5,
    },
  ]

  beforeEach(() => {
    vi.clearAllMocks()
    mockSearch = { tab: undefined }
    mockUseDiffSummary.mockReturnValue({ data: undefined })
    useCanvasStore.setState({
      canvases: {},
      unreadCanvasDocIds: {},
    })
  })

  it('renders Design & Plan card with canvas count and runs', () => {
    render(
      <TopicStageNav
        activeDocId="doc-1"
        canvasCount={2}
        chatId="chat-1"
        currentStage="design"
        executions={mockExecutions}
      />,
    )

    expect(screen.getByText('Design & Plan')).toBeInTheDocument()
    expect(screen.getByText('2 docs')).toBeInTheDocument()
    expect(screen.queryByText('Run 1')).not.toBeInTheDocument()
    expect(screen.getByText('1m ago')).toBeInTheDocument()
    expect(screen.getByText('just now')).toBeInTheDocument()
    expect(screen.getByText('$0.12')).toBeInTheDocument()
    expect(screen.getByText('$1.50')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /activity$/i })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: /changes$/i })).toHaveLength(2)
    expect(screen.getAllByText('PI')).toHaveLength(2)
  })

  it('shows a placeholder cost when an execution has no spend recorded', () => {
    render(
      <TopicStageNav
        canvasCount={0}
        chatId="chat-1"
        currentStage="design"
        executions={[
          {
            chatId: 'chat-1',
            harness: 'PI',
            id: 'exec-null-spend',
            startedAt: new Date(Date.now() - 20000).toISOString(),
            status: 'COMPLETED',
            totalSpend: null,
          },
        ]}
      />,
    )

    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('renders launch time on execution run cards', () => {
    render(
      <TopicStageNav
        canvasCount={0}
        chatId="chat-1"
        currentStage="design"
        executions={[
          {
            chatId: 'chat-1',
            completedAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString(),
            harness: 'PI',
            id: 'exec-old',
            startedAt: new Date(Date.now() - 3 * 3600 * 1000).toISOString(),
            status: 'COMPLETED',
          },
        ]}
      />,
    )

    expect(screen.getByText('3h ago')).toBeInTheDocument()
  })

  it('navigates to chat when clicking the Design & Plan card', () => {
    render(
      <TopicStageNav
        activeDocId="doc-1"
        canvasCount={2}
        chatId="chat-1"
        currentStage="execution"
        executions={mockExecutions}
      />,
    )

    fireEvent.click(screen.getByText('Design & Plan'))

    expect(mockNavigate).toHaveBeenCalledWith({
      params: { id: 'chat-1' },
      to: '/chats/$id',
    })
  })

  it('navigates to execution activity when clicking the wide zone of a run card', () => {
    render(
      <TopicStageNav
        activeExecutionId="exec-1"
        canvasCount={0}
        chatId="chat-1"
        currentStage="design"
        executions={mockExecutions}
      />,
    )

    // Activity zones are rendered in reverse order (most recent first), so
    // the first match corresponds to exec-2 (RUNNING)
    fireEvent.click(screen.getAllByRole('button', { name: /activity$/i })[0])

    expect(mockNavigate).toHaveBeenCalledWith({
      params: { executionId: 'exec-2', id: 'chat-1' },
      search: {},
      to: '/chats/$id/executions/$executionId',
    })
  })

  it('navigates to execution changes when clicking the narrow zone of a run card', () => {
    render(
      <TopicStageNav
        activeExecutionId="exec-1"
        canvasCount={0}
        chatId="chat-1"
        currentStage="design"
        executions={mockExecutions}
      />,
    )

    fireEvent.click(screen.getAllByRole('button', { name: /changes$/i })[0])

    expect(mockNavigate).toHaveBeenCalledWith({
      params: { executionId: 'exec-2', id: 'chat-1' },
      search: { tab: 'changes' },
      to: '/chats/$id/executions/$executionId',
    })
  })

  it('highlights the active zone on the selected execution card', () => {
    mockUseDiffSummary.mockReturnValue({
      data: { files: [{ path: 'a.ts' }, { path: 'b.ts' }] },
    })

    mockSearch = { tab: 'changes' }
    render(
      <TopicStageNav
        activeExecutionId="exec-2"
        canvasCount={0}
        chatId="chat-1"
        currentStage="execution"
        executions={mockExecutions}
      />,
    )

    // Most recent execution (exec-2) renders first
    const changesZone = screen.getAllByRole('button', { name: /changes$/i })[0]
    const activityZone = screen.getAllByRole('button', { name: /activity$/i })[0]
    expect(changesZone.className).toContain('bg-background')
    expect(activityZone.className).not.toContain('bg-background')
    expect(changesZone.textContent).toContain('2')
  })

  describe('design card docs dropdown', () => {
    it('renders inline Chat highlight and docs dropdown when canvases exist', () => {
      render(
        <TopicStageNav
          activeDocId="doc-1"
          canvasCount={2}
          canvasDocuments={[canvasDoc(), canvasDoc({ documentId: 'doc-2', title: 'Spec.md' })]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      expect(screen.getByRole('button', { name: /Design and Plan Stage/i })).toBeInTheDocument()
      expect(screen.getByText('Chat')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /canvas documents/i })).toBeInTheDocument()
    })

    it('does not render dropdown when no canvases exist', () => {
      render(
        <TopicStageNav
          canvasCount={0}
          canvasDocuments={[]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      expect(screen.queryByRole('button', { name: /canvas documents/i })).not.toBeInTheDocument()
    })

    it('navigates to the canvas document from the dropdown', async () => {
      render(
        <TopicStageNav
          activeDocId="doc-1"
          canvasCount={2}
          canvasDocuments={[canvasDoc(), canvasDoc({ documentId: 'doc-2', title: 'Spec.md' })]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      const docsButton = screen.getByRole('button', { name: /canvas documents/i })
      fireEvent.pointerDown(docsButton, { pointerType: 'mouse' })

      const specItem = await screen.findByRole('menuitem', { name: /spec\.md/i })
      fireEvent.click(specItem)

      expect(mockNavigate).toHaveBeenCalledWith({
        params: { docId: 'doc-2', id: 'chat-1' },
        to: '/chats/$id/canvas/$docId',
      })
    })

    it('navigates to chat when the card surface is clicked', () => {
      render(
        <TopicStageNav
          activeDocId="doc-1"
          canvasCount={1}
          canvasDocuments={[canvasDoc()]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      fireEvent.click(screen.getByText('Design & Plan'))

      expect(mockNavigate).toHaveBeenCalledWith({
        params: { id: 'chat-1' },
        to: '/chats/$id',
      })
    })

    it('shows activity pulse indicator and animate-pulse when unread documents exist', () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'chat-1': ['doc-1'],
        },
      })

      render(
        <TopicStageNav
          canvasCount={1}
          canvasDocuments={[canvasDoc()]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      expect(screen.getByTestId('canvas-pulse-indicator')).toBeInTheDocument()
      const docsButton = screen.getByRole('button', { name: /canvas documents/i })
      expect(docsButton.className).toContain('animate-pulse')
    })

    it('does not show activity pulse indicator when no unread documents exist', () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'chat-1': [],
        },
      })

      render(
        <TopicStageNav
          canvasCount={1}
          canvasDocuments={[canvasDoc()]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      expect(screen.queryByTestId('canvas-pulse-indicator')).not.toBeInTheDocument()
      const docsButton = screen.getByRole('button', { name: /canvas documents/i })
      expect(docsButton.className).not.toContain('animate-pulse')
    })

    it('displays updated badge in dropdown for unread document and clears activity when clicked', async () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'chat-1': ['doc-2'],
        },
      })

      render(
        <TopicStageNav
          canvasCount={2}
          canvasDocuments={[
            canvasDoc({ documentId: 'doc-1', title: 'Plan.md' }),
            canvasDoc({ documentId: 'doc-2', title: 'Spec.md' }),
          ]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      const docsButton = screen.getByRole('button', { name: /canvas documents/i })
      fireEvent.pointerDown(docsButton, { pointerType: 'mouse' })

      expect(await screen.findByTestId('canvas-unread-badge-doc-2')).toBeInTheDocument()
      expect(screen.queryByTestId('canvas-unread-badge-doc-1')).not.toBeInTheDocument()

      const specItem = screen.getByRole('menuitem', { name: /spec\.md/i })
      fireEvent.click(specItem)

      expect(useCanvasStore.getState().unreadCanvasDocIds['chat-1']).toEqual([])
      expect(mockNavigate).toHaveBeenCalledWith({
        params: { docId: 'doc-2', id: 'chat-1' },
        to: '/chats/$id/canvas/$docId',
      })
    })

    it('clears unread activity when activeDocId is already viewing the unread document', () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'chat-1': ['doc-1'],
        },
      })

      render(
        <TopicStageNav
          activeDocId="doc-1"
          canvasCount={1}
          canvasDocuments={[canvasDoc()]}
          chatId="chat-1"
          currentStage="design"
          executions={mockExecutions}
        />,
      )

      expect(useCanvasStore.getState().unreadCanvasDocIds['chat-1']).toEqual([])
      expect(screen.queryByTestId('canvas-pulse-indicator')).not.toBeInTheDocument()
    })
  })
})
