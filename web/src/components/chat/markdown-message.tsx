import { Maximize2 } from 'lucide-react'
import mermaid from 'mermaid'
import { useTheme } from 'next-themes'
import { useEffect, useId, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import rehypeHighlight from 'rehype-highlight'
import remarkGfm from 'remark-gfm'

import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'

// Initialize mermaid once
mermaid.initialize({
  securityLevel: 'loose',
  startOnLoad: false,
})

const UNSAFE_BARE_TITLE_CHARS = /[()[\]{}:;#"']/

interface MarkdownMessageProps {
  content: string
}

export function MarkdownMessage({ content }: MarkdownMessageProps) {
  return (
    <div className="prose prose-xs dark:prose-invert max-w-none overflow-x-auto leading-relaxed">
      <ReactMarkdown
        components={{
          code({ children, className, ...props }) {
            const match = /language-(\w+)/.exec(className || '')
            const isMermaid = match && match[1] === 'mermaid'

            if (isMermaid && typeof children === 'string') {
              return <MermaidDiagram chart={children} />
            }

            return (
              <code className={className} {...props}>
                {children}
              </code>
            )
          },
        }}
        rehypePlugins={[rehypeHighlight]}
        remarkPlugins={[remarkGfm]}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}

export function sanitizeMermaidSource(source: string): string {
  let sanitized = source.replace(
    /^(\s*subgraph\s+)(.+?)(\s*)$/gm,
    (match, prefix: string, title: string, suffix: string) => {
      const trimmed = title.trim()
      return needsQuoting(trimmed) ? `${prefix}"${trimmed}"${suffix}` : match
    },
  )

  sanitized = sanitized.replace(
    /((?:[-=.~]+[>oox]?|[<oox]?[-=.~]+|[-=.~]+)\s*\|)([^|\r\n]+)(\|)/g,
    (match, arrowPrefix: string, label: string, arrowSuffix: string) => {
      const trimmed = label.trim()
      if (
        (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
        (trimmed.startsWith("'") && trimmed.endsWith("'"))
      ) {
        return match
      }
      if (UNSAFE_BARE_TITLE_CHARS.test(trimmed)) {
        const escaped = trimmed.replace(/"/g, '\\"')
        return `${arrowPrefix}"${escaped}"${arrowSuffix}`
      }
      return match
    },
  )

  return sanitized
}

function MermaidDiagram({ chart }: { chart: string }) {
  const [svg, setSvg] = useState<null | string>(null)
  const [error, setError] = useState<null | string>(null)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const { resolvedTheme } = useTheme()
  const id = useId()
  const renderCounter = useRef(0)

  useEffect(() => {
    let isCancelled = false
    renderCounter.current += 1
    const currentRenderId = renderCounter.current
    const cleanId = id.replace(/:/g, '')
    const svgId = `mermaid-${cleanId}-${currentRenderId}`

    const renderMermaid = async () => {
      try {
        const isDarkMode = resolvedTheme === 'dark'
        // Update theme dynamically if needed, though mermaid.initialize is called once at module level
        mermaid.initialize({
          theme: isDarkMode ? 'dark' : 'default',
        })
        const { svg: renderedSvg } = await mermaid.render(svgId, sanitizeMermaidSource(chart))
        if (!isCancelled) {
          setSvg(renderedSvg)
          setError(null)
        }
      } catch (err) {
        console.error('Mermaid rendering error:', err)
        if (!isCancelled) {
          setError(err instanceof Error ? err.message : String(err))
        }
      }
    }
    void renderMermaid()

    return () => {
      isCancelled = true
    }
  }, [chart, resolvedTheme, id])

  if (error) {
    return (
      <div className="border-destructive bg-destructive/10 text-destructive rounded-md border p-4 text-sm">
        <p className="font-semibold">Failed to render Mermaid diagram</p>
        <p className="mt-1 break-words opacity-80">{error}</p>
        <details className="mt-3">
          <summary className="cursor-pointer text-xs font-medium opacity-80">
            Show diagram source
          </summary>
          <pre className="bg-muted mt-2 max-h-64 overflow-auto rounded-md p-3 text-xs">
            <code>{chart}</code>
          </pre>
        </details>
      </div>
    )
  }

  return (
    <>
      <div className="group relative">
        <div
          className="mermaid-diagram flex justify-center"
          dangerouslySetInnerHTML={svg ? { __html: svg } : undefined}
        />
        {svg && (
          <button
            aria-label="View diagram fullscreen"
            className="bg-background/80 text-muted-foreground hover:text-foreground hover:bg-accent absolute top-2 right-2 rounded-md border p-1.5 opacity-0 shadow-sm transition-opacity group-hover:opacity-100"
            onClick={() => setIsFullscreen(true)}
            type="button"
          >
            <Maximize2 className="h-4 w-4" />
          </button>
        )}
      </div>

      <Dialog onOpenChange={setIsFullscreen} open={isFullscreen}>
        <DialogContent className="flex h-[95vh] max-h-[95vh] w-[95vw] max-w-[95vw] flex-col overflow-hidden sm:max-w-[95vw]">
          <DialogTitle className="sr-only">Mermaid diagram</DialogTitle>
          <div className="flex flex-1 items-center justify-center overflow-hidden">
            {svg && (
              <div
                className="mermaid-diagram-fullscreen flex h-full w-full items-center justify-center [&_svg]:!h-full [&_svg]:!w-full [&_svg]:!max-w-none"
                dangerouslySetInnerHTML={{ __html: svg }}
              />
            )}
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}

function needsQuoting(title: string): boolean {
  if (title.startsWith('"') || title.startsWith("'")) return false
  if (title.includes('[')) return false
  return UNSAFE_BARE_TITLE_CHARS.test(title)
}
