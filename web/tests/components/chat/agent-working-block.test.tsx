import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { WorkingItem } from '@/types/telemetry-types'

import { AgentWorkingBlock } from '@/components/chat/agent-working-block'

vi.mock('next-themes', () => ({
  useTheme: () => ({ resolvedTheme: 'light' }),
}))

// Mock mermaid to avoid actual rendering in tests (transitively used by MarkdownMessage).
vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: '<svg>Mocked Mermaid Diagram</svg>' }),
  },
}))

describe('AgentWorkingBlock', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('scrolls wide generated content horizontally instead of widening the page', () => {
    const items: WorkingItem[] = [{ id: 'thought-1', text: 'A generated thought', type: 'thought' }]
    const { container } = render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    expect(container.firstChild).toHaveClass('overflow-x-auto')
  })

  it('renders markdown formatting in a thought item', () => {
    const items: WorkingItem[] = [
      {
        id: 'thought-1',
        text: '**Assessing Performance Implications**\n\nSome details in *italics*.',
        type: 'thought',
      },
    ]

    render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    // Bold/italic markdown should be rendered as actual elements, not raw asterisks.
    expect(screen.getByText('Assessing Performance Implications').tagName).toBe('STRONG')
    expect(screen.getByText('italics').tagName).toBe('EM')
    expect(screen.queryByText(/\*\*Assessing/)).not.toBeInTheDocument()
  })

  it('renders markdown lists inside a thought item', () => {
    const items: WorkingItem[] = [
      {
        id: 'thought-1',
        text: '- First point\n- Second point',
        type: 'thought',
      },
    ]

    render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    expect(screen.getByText('First point').closest('li')).not.toBeNull()
    expect(screen.getByText('Second point').closest('li')).not.toBeNull()
  })

  it('renders multiple thought items independently', () => {
    const items: WorkingItem[] = [
      { id: 'thought-1', text: 'First **bold** thought', type: 'thought' },
      { id: 'thought-2', text: 'Second *italic* thought', type: 'thought' },
    ]

    render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    expect(screen.getByText('bold').tagName).toBe('STRONG')
    expect(screen.getByText('italic').tagName).toBe('EM')
  })

  it('renders tool items as plain text, not markdown', () => {
    const items: WorkingItem[] = [
      {
        status: 'running',
        taskId: 'task-1',
        thought: 'Checking the **repo** for issues',
        toolName: 'search_repo',
        type: 'tool',
      },
    ]

    render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    // Tool "thought" text is rendered verbatim (raw markdown syntax visible), not parsed.
    expect(screen.getByText('Checking the **repo** for issues')).toBeInTheDocument()
  })

  it('renders failed tool items with subtle amber indicator and collapsible details toggle', () => {
    const items: WorkingItem[] = [
      {
        errorMessage: 'File not found: /src/legacy/config.json',
        status: 'error',
        taskId: 'task-error-1',
        thought: 'Reading repository configuration',
        toolName: 'read_file',
        type: 'tool',
      },
    ]

    render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    // Check indicator icon and thought
    const errorIcon = screen.getByLabelText('Tool failed')
    expect(errorIcon).toBeInTheDocument()
    expect(errorIcon.getAttribute('class')).toContain('text-amber-500')
    expect(screen.getByText('Reading repository configuration')).toBeInTheDocument()

    // Check collapsible details
    const details = screen.getByTestId<HTMLDetailsElement>('tool-error-details')
    expect(details).toBeInTheDocument()
    expect(details.open).toBe(false)

    // Summary toggle displays (failed)
    const summary = screen.getByText('(failed)')
    expect(summary).toBeInTheDocument()

    // Toggle expands details
    fireEvent.click(summary)
    expect(details.open).toBe(true)
    expect(screen.getByText('File not found: /src/legacy/config.json')).toBeInTheDocument()
  })

  it('renders failed tool item without error message subtly without crashing', () => {
    const items: WorkingItem[] = [
      {
        status: 'error',
        taskId: 'task-error-2',
        thought: 'Probing endpoint',
        toolName: 'http_request',
        type: 'tool',
      },
    ]

    render(
      <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
    )

    expect(screen.getByLabelText('Tool failed')).toBeInTheDocument()
    expect(screen.getByText('Probing endpoint')).toBeInTheDocument()
    expect(screen.getByText('(failed)')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-error-details')).not.toBeInTheDocument()
  })

  it('renders formatted thoughts in the completed/expanded view too', async () => {
    const items: WorkingItem[] = [
      { id: 'thought-1', text: 'A **finished** thought', type: 'thought' },
    ]

    render(
      <AgentWorkingBlock
        endTime={Date.now()}
        isStreaming={false}
        items={items}
        startTime={Date.now() - 1000}
      />,
    )

    // Expand the collapsed "Agent worked for..." summary.
    fireEvent.click(screen.getByRole('button'))

    const strong = await screen.findByText('finished')
    expect(strong.tagName).toBe('STRONG')
  })

  describe('collapse/expand behavior', () => {
    const items: WorkingItem[] = [
      { id: 'thought-1', text: 'Thinking it through', type: 'thought' },
    ]

    it('is always expanded while streaming, with no toggle button', () => {
      render(<AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />)

      expect(screen.getByText('Agent working...')).toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      expect(screen.getByTestId('agent-working-items')).toHaveStyle({ gridTemplateRows: '1fr' })
    })

    it('collapses by default once the turn finishes', () => {
      render(
        <AgentWorkingBlock
          endTime={Date.now()}
          isStreaming={false}
          items={items}
          startTime={Date.now() - 1000}
        />,
      )

      expect(screen.getByText(/agent worked for/i)).toBeInTheDocument()
      expect(screen.getByTestId('agent-working-items')).toHaveStyle({ gridTemplateRows: '0fr' })
    })

    it('expands and re-collapses when the summary button is clicked', () => {
      render(
        <AgentWorkingBlock
          endTime={Date.now()}
          isStreaming={false}
          items={items}
          startTime={Date.now() - 1000}
        />,
      )

      const button = screen.getByRole('button')
      const wrapper = screen.getByTestId('agent-working-items')
      expect(wrapper).toHaveStyle({ gridTemplateRows: '0fr' })

      fireEvent.click(button)
      expect(wrapper).toHaveStyle({ gridTemplateRows: '1fr' })

      fireEvent.click(button)
      expect(wrapper).toHaveStyle({ gridTemplateRows: '0fr' })
    })

    it('applies a CSS transition to the collapsible content, not an instant snap', () => {
      render(
        <AgentWorkingBlock
          endTime={Date.now()}
          isStreaming={false}
          items={items}
          startTime={Date.now() - 1000}
        />,
      )

      expect(screen.getByTestId('agent-working-items').className).toContain(
        'transition-[grid-template-rows]',
      )
    })

    it('does not auto-collapse while streaming even if it later would default to collapsed', () => {
      const { rerender } = render(
        <AgentWorkingBlock endTime={null} isStreaming items={items} startTime={Date.now()} />,
      )
      expect(screen.getByTestId('agent-working-items')).toHaveStyle({ gridTemplateRows: '1fr' })

      // Turn finishes - only now should it be eligible to collapse (to its default state).
      rerender(
        <AgentWorkingBlock
          endTime={Date.now()}
          isStreaming={false}
          items={items}
          startTime={Date.now() - 1000}
        />,
      )
      expect(screen.getByTestId('agent-working-items')).toHaveStyle({ gridTemplateRows: '0fr' })
    })
  })
})
