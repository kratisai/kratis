import type { RenderResult } from 'mermaid'

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import mermaid from 'mermaid'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getMermaidConfig, MarkdownMessage } from '@/components/chat/markdown-message'
import { recognize } from '@/lib/mermaid'

const mockResolvedTheme = vi.fn().mockReturnValue('light')

function mockMermaidRenderResult(svg: string): RenderResult {
  return { diagramType: 'flowchart', svg }
}

vi.mock('next-themes', () => ({
  useTheme: () => ({
    resolvedTheme: mockResolvedTheme(),
  }),
}))

// Mock mermaid to avoid actual rendering in tests
vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ diagramType: 'flowchart', svg: '<svg>Mocked Mermaid Diagram</svg>' }),
  },
}))

describe('MarkdownMessage', () => {
  beforeEach(() => {
    vi.mocked(mermaid.render).mockReset()
    vi.mocked(mermaid.render).mockResolvedValue(mockMermaidRenderResult('<svg>Mocked Mermaid Diagram</svg>'))
    mockResolvedTheme.mockReturnValue('light')
  })

  it('renders regular markdown content', () => {
    render(<MarkdownMessage content="# Hello World\n\nThis is a test." />)
    expect(screen.getByRole('heading', { name: /hello world/i })).toBeInTheDocument()
    expect(screen.getByText(/this is a test/i)).toBeInTheDocument()
  })

  it('renders content inside a horizontally scrollable container so wide elements cannot widen the page', () => {
    const { container } = render(<MarkdownMessage content="hello **world**" />)
    const prose = container.querySelector('.prose')
    expect(prose).not.toBeNull()
    expect(prose).toHaveClass('overflow-x-auto', 'prose-xs', 'max-w-none')
  })

  it.each([['document' as const, 'prose-document'], ['wiki' as const, 'prose-wiki']])(
    'applies the %s density class for long-form reading',
    (variant, expectedClass) => {
      const { container } = render(<MarkdownMessage content="hello" variant={variant} />)
      expect(container.querySelector('.prose')).toHaveClass(expectedClass)
    },
  )

  it('wraps tables in a rounded scroll container', () => {
    const { container } = render(<MarkdownMessage content={'| a | b |\n|---|---|\n| 1 | 2 |'} />)
    const wrapper = container.querySelector('.md-table-wrapper')
    expect(wrapper).not.toBeNull()
    expect(wrapper?.querySelector('table')).not.toBeNull()
  })

  it('renders task-list checkboxes', () => {
    render(<MarkdownMessage content={'- [x] done\n- [ ] todo'} />)
    expect(screen.getAllByRole('checkbox').map((box) => (box as HTMLInputElement).checked)).toEqual(
      [true, false],
    )
  })

  it.each([
    ['NOTE', 'note', 'Note'],
    ['TIP', 'tip', 'Tip'],
    ['IMPORTANT', 'important', 'Important'],
    ['WARNING', 'warning', 'Warning'],
    ['CAUTION', 'caution', 'Caution'],
  ])('renders a %s GitHub callout with a label', (marker, type, label) => {
    const { container } = render(
      <MarkdownMessage content={`> [!${marker}]\n> Stay calm and carry on.`} />,
    )
    const quote = container.querySelector(`blockquote[data-callout="${type}"]`)
    expect(quote).not.toBeNull()
    expect(quote?.textContent).toContain(label)
    expect(quote?.textContent).toContain('Stay calm and carry on.')
    expect(quote?.textContent).not.toContain(`[!${marker}]`)
  })

  it('renders plain blockquotes without a callout treatment', () => {
    const { container } = render(<MarkdownMessage content="> Just a quote." />)
    expect(container.querySelector('blockquote[data-callout]')).toBeNull()
    expect(screen.getByText('Just a quote.')).toBeInTheDocument()
  })

  it('exposes warm-paper mermaid variables for light mode', () => {
    expect(getMermaidConfig(false)).toMatchObject({
      theme: 'base',
      themeVariables: expect.objectContaining({
        edgeLabelBackground: '#faf9f6',
        primaryColor: '#efe9dc',
      }),
    })
  })

  it('exposes graphite mermaid variables for dark mode', () => {
    expect(getMermaidConfig(true)).toMatchObject({
      theme: 'base',
      themeVariables: expect.objectContaining({
        edgeLabelBackground: '#242424',
        primaryColor: '#2e2c28',
      }),
    })
  })

  it('renders a mermaid diagram when code block language is mermaid', async () => {
    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    // Wait for the mermaid diagram to be rendered
    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })
  })

  it('defers mermaid rendering while its fence is still streaming', async () => {
    const partial = '```mermaid\nflowchart TD\nA --> B'
    const { rerender } = render(<MarkdownMessage content={partial} />)

    expect(mermaid.render).not.toHaveBeenCalled()
    expect(screen.getByText('Incomplete Mermaid diagram')).toBeInTheDocument()

    rerender(<MarkdownMessage content={`${partial}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })
    expect(mermaid.render).toHaveBeenCalledTimes(1)
  })

  it.each([
    '> ```mermaid\n> flowchart TD\n> A --> B',
    '- ```mermaid\n  flowchart TD\n  A --> B',
  ])('does not render an unclosed nested Mermaid fence', (content) => {
    render(<MarkdownMessage content={content} />)
    expect(screen.getByText('Incomplete Mermaid diagram')).toBeInTheDocument()
    expect(mermaid.render).not.toHaveBeenCalled()
  })

  it('detects a streaming Mermaid fence after an implicitly closed blockquote fence', () => {
    const content = '> ```text\noutside\n```mermaid\nflowchart TD\nA --> B'
    render(<MarkdownMessage content={content} />)
    expect(screen.getByText('Incomplete Mermaid diagram')).toBeInTheDocument()
    expect(mermaid.render).not.toHaveBeenCalled()
  })

  it('renders a Mermaid fence implicitly closed by leaving its blockquote', async () => {
    const content = '> ```mermaid\n> flowchart TD\n> A --> B\n\nnext'
    render(<MarkdownMessage content={content} />)
    await screen.findByText('Mocked Mermaid Diagram')
    expect(screen.queryByText('Incomplete Mermaid diagram')).not.toBeInTheDocument()
    expect(mermaid.render).toHaveBeenCalledTimes(1)
    expect(screen.getByText('next')).toBeInTheDocument()
  })

  it('renders completed diagrams while a later mermaid fence is still streaming', async () => {
    const completed = '```mermaid\nflowchart TD\nA --> B\n```'
    const streaming = '```mermaid\nflowchart TD\nC --> D'
    render(<MarkdownMessage content={`${completed}\n\n${streaming}`} />)

    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })
    expect(mermaid.render).toHaveBeenCalledTimes(1)
    expect(vi.mocked(mermaid.render).mock.calls[0]?.[1]).toContain('A --> B')
    expect(screen.getByText('Incomplete Mermaid diagram')).toBeInTheDocument()
  })

  it('does not confuse an identical streaming diagram with a completed diagram', async () => {
    const chart = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\`\n\n\`\`\`mermaid\n${chart}`} />)
    await screen.findByText('Mocked Mermaid Diagram')
    expect(screen.getByText('Incomplete Mermaid diagram')).toBeInTheDocument()
    expect(mermaid.render).toHaveBeenCalledTimes(1)
  })

  it('renders completed diagrams while a non-mermaid fence is still streaming', async () => {
    const content = '```mermaid\nflowchart TD\nA --> B\n```\n\n```text\nstill streaming'
    render(<MarkdownMessage content={content} />)

    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })
    expect(mermaid.render).toHaveBeenCalledTimes(1)
  })

  it('renders regular code blocks normally', () => {
    render(<MarkdownMessage content="```javascript\nconst x = 1;\n```" />)
    expect(screen.getByText(/const x = 1;/)).toBeInTheDocument()
  })

  it('re-renders mermaid diagram when theme changes', async () => {
    mockResolvedTheme.mockReturnValue('light')
    const mermaidContent = 'flowchart TD\nA --> B'
    const { rerender } = render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })

    expect(mermaid.initialize).toHaveBeenCalledWith(
      expect.objectContaining({
        theme: 'base',
        themeVariables: expect.objectContaining({ primaryColor: '#efe9dc' }),
      }),
    )

    // Change theme to dark
    mockResolvedTheme.mockReturnValue('dark')

    // Trigger a re-render to apply the theme change
    rerender(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    await waitFor(() => {
      expect(mermaid.initialize).toHaveBeenCalledWith(
        expect.objectContaining({
          theme: 'base',
          themeVariables: expect.objectContaining({ primaryColor: '#2e2c28' }),
        }),
      )
    })
  })

  it('cancels previous rendering if component re-renders with new theme before first render completes', async () => {
    let resolveFirst: (value: RenderResult) => void = () => {}
    const firstPromise = new Promise<RenderResult>((resolve) => {
      resolveFirst = resolve
    })

    let resolveSecond: (value: RenderResult) => void = () => {}
    const secondPromise = new Promise<RenderResult>((resolve) => {
      resolveSecond = resolve
    })

    let callCount = 0
    vi.mocked(mermaid.render).mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return firstPromise
      }
      return secondPromise
    })

    const mermaidContent = 'flowchart TD\nA --> B'
    mockResolvedTheme.mockReturnValue('light')
    const { container, rerender } = render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    // The first render starts but is not yet resolved.

    // Change theme to dark and re-render immediately to cancel the first render
    mockResolvedTheme.mockReturnValue('dark')
    rerender(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    // Resolve the first render with a distinct SVG content
    resolveFirst(mockMermaidRenderResult('<svg>First Rendered SVG (Light)</svg>'))

    // Wait a brief moment to ensure the first render's microtasks run
    await new Promise((resolve) => setTimeout(resolve, 10))

    // The first render's output should NOT be written to the DOM because it was cancelled
    expect(container.innerHTML).not.toContain('First Rendered SVG')

    // Resolve the second render
    resolveSecond(mockMermaidRenderResult('<svg>Second Rendered SVG (Dark)</svg>'))

    // The second render's output should be written to the DOM
    await waitFor(() => {
      expect(screen.getByText('Second Rendered SVG (Dark)')).toBeInTheDocument()
    })
  })

  it('shows a fullscreen expand button once the diagram has rendered', async () => {
    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /view diagram fullscreen/i })).toBeInTheDocument()
    })
  })

  it('shows a copy button that writes canonical diagram text to the clipboard', async () => {
    const user = userEvent.setup()
    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    const copyButton = await screen.findByRole('button', { name: /copy diagram source/i })
    expect(copyButton.querySelector('.lucide-copy')).toBeInTheDocument()
    await user.click(copyButton)

    expect(await navigator.clipboard.readText()).toBe(mermaidContent)

    await waitFor(() => {
      expect(copyButton.querySelector('.lucide-check')).toBeInTheDocument()
    })
  })

  it('does not show a copy button before the diagram has rendered', () => {
    let resolveRender: (value: RenderResult) => void = () => {}
    vi.mocked(mermaid.render).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveRender = resolve
        }),
    )

    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    expect(screen.queryByRole('button', { name: /copy diagram source/i })).not.toBeInTheDocument()

    // Cleanup: resolve the pending render so it doesn't leak into other tests
    resolveRender(mockMermaidRenderResult('<svg>Mocked Mermaid Diagram</svg>'))
  })

  it('opens a fullscreen dialog with the diagram when the expand button is clicked', async () => {
    const user = userEvent.setup()
    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    const expandButton = await screen.findByRole('button', { name: /view diagram fullscreen/i })
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    // The diagram svg is rendered both inline and inside the fullscreen dialog
    expect(screen.getAllByText('Mocked Mermaid Diagram')).toHaveLength(2)
  })

  it('closes the fullscreen dialog when dismissed', async () => {
    const user = userEvent.setup()
    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    const expandButton = await screen.findByRole('button', { name: /view diagram fullscreen/i })
    await user.click(expandButton)

    const dialog = await screen.findByRole('dialog')
    const closeButton = within(dialog).getByRole('button', { name: /close/i })
    await user.click(closeButton)

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('does not show a fullscreen button before the diagram has rendered', () => {
    let resolveRender: (value: RenderResult) => void = () => {}
    vi.mocked(mermaid.render).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveRender = resolve
        }),
    )

    const mermaidContent = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    expect(screen.queryByRole('button', { name: /view diagram fullscreen/i })).not.toBeInTheDocument()

    // Cleanup: resolve the pending render so it doesn't leak into other tests
    resolveRender(mockMermaidRenderResult('<svg>Mocked Mermaid Diagram</svg>'))
  })

  it('passes C4 arrow and pipe text through without modification', async () => {
    const chart = 'C4Context\nPerson(user, "Client --o|read (fast)| Server")'
    const result = recognize(chart)
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    expect(result.renderText).toBe(chart)
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} />)
    await screen.findByText('Mocked Mermaid Diagram')
    expect(mermaid.render).toHaveBeenCalledWith(expect.any(String), result.renderText)
  })

  it('passes exactly the recognized render text to mermaid.render', async () => {
    const chart = 'flowchart LR\nA["Start & go"] -->|"yes; now"| B["Finish"]'
    const result = recognize(chart)
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} />)

    await screen.findByText('Mocked Mermaid Diagram')
    expect(mermaid.render).toHaveBeenCalledWith(expect.any(String), result.renderText)
    expect(result.renderText).toContain('Start #38; go')
    expect(result.renderText).toContain('yes#59; now')
  })

  it.each(['chat', 'document', 'wiki'] as const)(
    'rejects unsafe and unsupported diagrams in %s without invoking Mermaid',
    (variant) => {
      const chart = 'flowchart LR\nA --> B\nclick A "https://example.com"'
      const { container } = render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} variant={variant} />)
      expect(screen.getByText('Failed to render Mermaid diagram')).toBeInTheDocument()
      expect(mermaid.render).not.toHaveBeenCalled()
      expect(container.querySelector('.mermaid-diagram')).toBeNull()
      expect(container.querySelector('details code')?.textContent).toBe(chart)
      expect(screen.queryByRole('button', { name: /copy diagram source/i })).not.toBeInTheDocument()
    },
  )

  it('replaces an earlier rendered diagram with a closed error for invalid updates', async () => {
    const { container, rerender } = render(<MarkdownMessage content={'```mermaid\nflowchart LR\nA --> B\n```'} />)
    await screen.findByText('Mocked Mermaid Diagram')
    rerender(<MarkdownMessage content={'```mermaid\nflowchart LR\nclick A "javascript:alert(1)"\n```'} />)
    expect(screen.getByText('Failed to render Mermaid diagram')).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeNull()
    expect(mermaid.render).toHaveBeenCalledTimes(1)
  })

  it('copies canonicalized render text instead of the original source', async () => {
    const user = userEvent.setup()
    const chart = 'flowchart LR\n  A["Start & go"] --> B'
    const result = recognize(chart)
    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error(result.error)
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} />)
    await user.click(await screen.findByRole('button', { name: /copy diagram source/i }))
    expect(await navigator.clipboard.readText()).toBe(result.renderText)
    expect(vi.mocked(mermaid.render).mock.calls[0]?.[1]).toBe(result.renderText)
  })

  it('shows the raw diagram source when rendering fails', async () => {
    vi.mocked(mermaid.render).mockRejectedValue(new Error('Parse error on line 6'))

    const chart = 'flowchart TD\nA --> B'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByText('Failed to render Mermaid diagram')).toBeInTheDocument()
    })
    expect(screen.getByText(/Parse error on line 6/)).toBeInTheDocument()

    const summary = screen.getByText('Show diagram source')
    await userEvent.click(summary)
    expect(screen.getByText(/A --> B/)).toBeInTheDocument()
  })
})
