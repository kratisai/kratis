import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CanvasView } from '@/components/views/canvas-view'
import { useCanvasStore } from '@/store/canvas-store'

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')
  return {
    ...actual,
    useParams: vi.fn(() => ({ docId: 'doc-1', id: 'session-1' })),
  }
})

vi.mock('@/hooks/use-providers', () => ({
  useProviders: vi.fn(() => ({ data: [] })),
}))

vi.mock('@/hooks/use-environments', () => ({
  useEnvironments: vi.fn(() => ({ data: [] })),
}))

vi.mock('@/hooks/use-harnesses', () => ({
  useHarnesses: vi.fn(() => ({ data: [] })),
}))

vi.mock('@/hooks/use-credentials', () => ({
  useCredentials: vi.fn(() => ({ data: [] })),
}))

vi.mock('@/hooks/use-executions', () => ({
  useChatExecutions: vi.fn(() => ({
    data: [],
    refetch: vi.fn(),
  })),
}))

describe('CanvasView', () => {
  beforeEach(() => {
    useCanvasStore.setState({ canvases: {}, unreadCanvasDocIds: {} })
  })

  it('renders the canvas document addressed by the URL docId', () => {
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'SPEC',
            chatId: 'session-1',
            content: '# Plan',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'Plan Canvas',
            version: 1,
          },
        ],
      },
    })

    render(<CanvasView />)
    expect(screen.getByText('Plan Canvas')).toBeInTheDocument()
    expect(screen.getByText('Plan')).toBeInTheDocument()
  })

  it('shows the empty state when the chat has no canvas documents', () => {
    render(<CanvasView />)
    expect(screen.getByText(/no canvas documents yet/i)).toBeInTheDocument()
  })

  it('clears canvas activity for the viewed document on mount', () => {
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'SPEC',
            chatId: 'session-1',
            content: '# Plan',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'Plan Canvas',
            version: 1,
          },
        ],
      },
      unreadCanvasDocIds: {
        'session-1': ['doc-1'],
      },
    })

    render(<CanvasView />)
    expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual([])
  })
})
