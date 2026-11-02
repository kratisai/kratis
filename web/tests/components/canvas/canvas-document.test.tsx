import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CanvasDocument } from '@/components/canvas/canvas-document'
import { useCanvasStore } from '@/store/canvas-store'

vi.mock('sonner', () => ({
  toast: { success: vi.fn() },
}))

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: '<svg>Mocked Mermaid Diagram</svg>' }),
  },
}))

describe('CanvasDocument', () => {
  beforeEach(() => {
    useCanvasStore.setState({ canvases: {} })
  })

  it('renders not found when document does not exist', () => {
    useCanvasStore.setState({ canvases: { 'session-1': [] } })

    render(<CanvasDocument chatId="session-1" documentId="doc-1" />)
    expect(screen.getByText(/document not found/i)).toBeInTheDocument()
  })

  it('renders markdown content when document exists', () => {
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'DOCUMENT',
            chatId: 'session-1',
            content: '# Hello\n\nThis is a test.',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'Test Document',
            version: 1,
          },
        ],
      },
    })

    render(<CanvasDocument chatId="session-1" documentId="doc-1" />)
    expect(screen.getByText('Hello')).toBeInTheDocument()
    expect(screen.getByText('This is a test.')).toBeInTheDocument()
  })

  describe('copy button', () => {
    beforeEach(() => {
      useCanvasStore.setState({
        canvases: {
          'session-1': [
            {
              canvasType: 'DOCUMENT',
              chatId: 'session-1',
              content:
                '# Hello\n\n- one\n- two\n\n```mermaid\nflowchart LR\nA["One & two"] --> B\n```',
              documentId: 'doc-1',
              isNewRepo: false,
              title: 'Test Document',
              version: 1,
            },
          ],
        },
      })
    })

    it('renders a copy button at the top of the document', () => {
      render(<CanvasDocument chatId="session-1" documentId="doc-1" />)

      expect(screen.getByRole('button', { name: /copy document/i })).toBeInTheDocument()
    })

    it('copies canonical Mermaid text with the markdown content when clicked', async () => {
      const user = userEvent.setup()
      render(<CanvasDocument chatId="session-1" documentId="doc-1" />)

      const copyButton = screen.getByRole('button', { name: /copy document/i })
      await user.click(copyButton)

      expect(await navigator.clipboard.readText()).toBe(
        '# Hello\n\n- one\n- two\n\n```mermaid\nflowchart LR\nA["One #38; two"] --> B\n```',
      )
    })

    it('briefly swaps the copy icon for a check icon after copying', async () => {
      const user = userEvent.setup()
      render(<CanvasDocument chatId="session-1" documentId="doc-1" />)

      const copyButton = screen.getByRole('button', { name: /copy document/i })
      expect(copyButton.querySelector('.lucide-copy')).toBeInTheDocument()

      await user.click(copyButton)

      await waitFor(() => {
        expect(copyButton.querySelector('.lucide-check')).toBeInTheDocument()
      })
    })
  })
})
