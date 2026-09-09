import type { RenderResult } from 'mermaid'

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import mermaid from 'mermaid'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { MarkdownMessage, sanitizeMermaidSource } from '@/components/chat/markdown-message'

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

  it('renders a mermaid diagram when code block language is mermaid', async () => {
    const mermaidContent = 'graph TD;\nA-->B;'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    // Wait for the mermaid diagram to be rendered
    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })
  })

  it('renders regular code blocks normally', () => {
    render(<MarkdownMessage content="```javascript\nconst x = 1;\n```" />)
    expect(screen.getByText(/const x = 1;/)).toBeInTheDocument()
  })

  it('re-renders mermaid diagram when theme changes', async () => {
    mockResolvedTheme.mockReturnValue('light')
    const mermaidContent = 'graph TD;\nA-->B;'
    const { rerender } = render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })

    // Check that mermaid.initialize was called with default theme
    expect(mermaid.initialize).toHaveBeenCalledWith(expect.objectContaining({ theme: 'default' }))

    // Change theme to dark
    mockResolvedTheme.mockReturnValue('dark')

    // Trigger a re-render to apply the theme change
    rerender(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    await waitFor(() => {
      expect(mermaid.initialize).toHaveBeenCalledWith(expect.objectContaining({ theme: 'dark' }))
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

    const mermaidContent = 'graph TD;\nA-->B;'
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
    const mermaidContent = 'graph TD;\nA-->B;'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /view diagram fullscreen/i })).toBeInTheDocument()
    })
  })

  it('opens a fullscreen dialog with the diagram when the expand button is clicked', async () => {
    const user = userEvent.setup()
    const mermaidContent = 'graph TD;\nA-->B;'
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
    const mermaidContent = 'graph TD;\nA-->B;'
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

    const mermaidContent = 'graph TD;\nA-->B;'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${mermaidContent}\n\`\`\``} />)

    expect(screen.queryByRole('button', { name: /view diagram fullscreen/i })).not.toBeInTheDocument()

    // Cleanup: resolve the pending render so it doesn't leak into other tests
    resolveRender(mockMermaidRenderResult('<svg>Mocked Mermaid Diagram</svg>'))
  })

  describe('sanitizeMermaidSource', () => {
    it('quotes a bare subgraph title containing parentheses', () => {
      const source = 'graph TD\n    subgraph Control Plane Tier (Spring Boot)\n        API[REST Controllers]\n    end'
      expect(sanitizeMermaidSource(source)).toContain('subgraph "Control Plane Tier (Spring Boot)"')
    })

    it('quotes a bare subgraph title containing a colon', () => {
      expect(sanitizeMermaidSource('subgraph Control Plane: API')).toContain('subgraph "Control Plane: API"')
    })

    it('leaves already-quoted titles untouched', () => {
      const source = 'subgraph "Control Plane Tier (Spring Boot)"'
      expect(sanitizeMermaidSource(source)).toBe(source)
    })

    it('leaves bracketed titles untouched', () => {
      const source = 'subgraph cpt[Control Plane Tier (Spring Boot)]'
      expect(sanitizeMermaidSource(source)).toBe(source)
    })

    it('leaves plain titles untouched', () => {
      const source = 'subgraph Client Tier'
      expect(sanitizeMermaidSource(source)).toBe(source)
    })

    it('does not modify simple node edges without labels', () => {
      const source = 'graph TD\n    A --> B'
      expect(sanitizeMermaidSource(source)).toBe(source)
    })

    it('quotes unquoted edge labels containing parentheses', () => {
      const source = 'SandboxProv -->|Generate Virtual Key (TTL + Budget)| LiteLLMSvc'
      expect(sanitizeMermaidSource(source)).toBe(
        'SandboxProv -->|"Generate Virtual Key (TTL + Budget)"| LiteLLMSvc',
      )
    })

    it('quotes unquoted edge labels containing colons, brackets, or braces', () => {
      const source = 'A -->|payload [0]| B\nB -->|config {key: val}| C\nC -->|port: 8080| D'
      expect(sanitizeMermaidSource(source)).toBe(
        'A -->|"payload [0]"| B\nB -->|"config {key: val}"| C\nC -->|"port: 8080"| D',
      )
    })

    it('leaves already-quoted edge labels untouched', () => {
      const doubleQuoted = 'SandboxProv -->|"Generate Virtual Key (TTL + Budget)"| LiteLLMSvc'
      const singleQuoted = "SandboxProv -->|'Generate Virtual Key (TTL + Budget)'| LiteLLMSvc"
      expect(sanitizeMermaidSource(doubleQuoted)).toBe(doubleQuoted)
      expect(sanitizeMermaidSource(singleQuoted)).toBe(singleQuoted)
    })

    it('leaves plain edge labels without special characters untouched', () => {
      const source = 'LiteLLMSvc -->|Register Upstream Model| LiteLLMGW'
      expect(sanitizeMermaidSource(source)).toBe(source)
    })

    it('quotes multiple unsafe edge labels on the same line', () => {
      const source = 'A -->|step 1 (init)| B -->|step 2 [run]| C'
      expect(sanitizeMermaidSource(source)).toBe('A -->|"step 1 (init)"| B -->|"step 2 [run]"| C')
    })
  })

  it('passes sanitized mermaid source to mermaid.render', async () => {
    const chart = 'graph TD\n    subgraph Control Plane Tier (Spring Boot)\n        API[REST Controllers]\n    end'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByText('Mocked Mermaid Diagram')).toBeInTheDocument()
    })

    const renderCall = vi.mocked(mermaid.render).mock.calls.at(-1)
    expect(renderCall?.[1]).toContain('subgraph "Control Plane Tier (Spring Boot)"')
  })

  it('shows the raw diagram source when rendering fails', async () => {
    vi.mocked(mermaid.render).mockRejectedValue(new Error('Parse error on line 6'))

    const chart = 'graph TD\n    subgraph Broken (Diagram)\n        A\n    end'
    render(<MarkdownMessage content={`\`\`\`mermaid\n${chart}\n\`\`\``} />)

    await waitFor(() => {
      expect(screen.getByText('Failed to render Mermaid diagram')).toBeInTheDocument()
    })
    expect(screen.getByText(/Parse error on line 6/)).toBeInTheDocument()

    const summary = screen.getByText('Show diagram source')
    await userEvent.click(summary)
    expect(screen.getByText(/subgraph Broken \(Diagram\)/)).toBeInTheDocument()
  })
})
