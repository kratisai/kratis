import type { ReactElement, ReactNode } from 'react'

import { Check, Copy, Maximize2 } from 'lucide-react'
import mermaid from 'mermaid'
import { useTheme } from 'next-themes'
import { Children, cloneElement, isValidElement, useEffect, useId, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import rehypeHighlight from 'rehype-highlight'
import remarkGfm from 'remark-gfm'

import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'
import { useResetAfterDelay } from '@/hooks/use-reset-after-delay'
import { copyToClipboard } from '@/lib/clipboard'
import { recognize } from '@/lib/mermaid'
import { cn } from '@/lib/utils'

type MarkdownVariant = 'chat' | 'document' | 'wiki'

const VARIANT_SIZES: Record<MarkdownVariant, string> = {
  chat: 'prose-xs',
  document: 'prose-document',
  wiki: 'prose-wiki',
}

const LIGHT_MERMAID_VARS = {
  activationBkgColor: '#e7e5e4',
  activationBorderColor: '#a8a29e',
  actorBkg: '#fffdf7',
  actorBorder: '#d8cfb8',
  actorLineColor: '#78716c',
  actorTextColor: '#1c1917',
  background: 'transparent',
  clusterBkg: '#f7f5ef',
  clusterBorder: '#d4b36a',
  edgeLabelBackground: '#faf9f6',
  fontFamily: 'Inter, system-ui, sans-serif',
  labelBoxBkgColor: '#efe9dc',
  labelBoxBorderColor: '#d8cfb8',
  labelTextColor: '#1c1917',
  lineColor: '#78716c',
  loopTextColor: '#57534e',
  mainBkg: '#fffdf7',
  nodeBkg: '#fffdf7',
  nodeBorder: '#d8cfb8',
  noteBkgColor: '#fef3c7',
  noteBorderColor: '#d4b36a',
  noteTextColor: '#44403c',
  primaryBorderColor: '#d8cfb8',
  primaryColor: '#efe9dc',
  primaryTextColor: '#1c1917',
  secondaryColor: '#f7f5ef',
  sequenceNumberColor: '#fffdf7',
  signalColor: '#44403c',
  signalTextColor: '#44403c',
  tertiaryColor: '#f5f4f0',
  titleColor: '#1c1917',
}

const DARK_MERMAID_VARS = {
  activationBkgColor: '#3a3a3a',
  activationBorderColor: '#6b6b6b',
  actorBkg: '#2e2c28',
  actorBorder: '#5a5a5a',
  actorLineColor: '#a3a3a3',
  actorTextColor: '#eeeeee',
  background: 'transparent',
  clusterBkg: '#242422',
  clusterBorder: '#a68a4d',
  edgeLabelBackground: '#242424',
  fontFamily: 'Inter, system-ui, sans-serif',
  labelBoxBkgColor: '#2e2c28',
  labelBoxBorderColor: '#5a5a5a',
  labelTextColor: '#eeeeee',
  lineColor: '#a3a3a3',
  loopTextColor: '#d4d4d4',
  mainBkg: '#26241f',
  nodeBkg: '#2e2c28',
  nodeBorder: '#5a5a5a',
  noteBkgColor: '#3a3525',
  noteBorderColor: '#a68a4d',
  noteTextColor: '#eeeeee',
  primaryBorderColor: '#5a5a5a',
  primaryColor: '#2e2c28',
  primaryTextColor: '#eeeeee',
  secondaryColor: '#26241f',
  sequenceNumberColor: '#242424',
  signalColor: '#d4d4d4',
  signalTextColor: '#d4d4d4',
  tertiaryColor: '#2a2a2a',
  titleColor: '#eeeeee',
}

export function getMermaidConfig(isDark: boolean) {
  return {
    theme: 'base' as const,
    themeVariables: isDark ? DARK_MERMAID_VARS : LIGHT_MERMAID_VARS,
  }
}

mermaid.initialize({
  securityLevel: 'loose',
  startOnLoad: false,
  ...getMermaidConfig(false),
})

const CALLOUT_RE = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*:?\s*/i

const CALLOUT_LABELS: Record<string, string> = {
  caution: 'Caution',
  important: 'Important',
  note: 'Note',
  tip: 'Tip',
  warning: 'Warning',
}

interface MarkdownMessageProps {
  content: string
  variant?: MarkdownVariant
}

export function MarkdownMessage({ content, variant = 'chat' }: MarkdownMessageProps) {
  const unclosedMermaid = findUnclosedMermaidFence(content)

  return (
    <div
      className={cn(
        'prose dark:prose-invert max-w-none overflow-x-auto leading-relaxed',
        VARIANT_SIZES[variant],
      )}
    >
      <ReactMarkdown
        components={{
          blockquote({ children, node: _node, ...props }) {
            const callout = detectCallout(children)
            if (!callout) {
              return <blockquote {...props}>{children}</blockquote>
            }
            return (
              <blockquote {...props} data-callout={callout.type}>
                <span className="md-callout-label">{callout.label}</span>
                {callout.content}
              </blockquote>
            )
          },
          code({ children, className, node, ...props }) {
            const match = /language-([\w+-]+)/.exec(className || '')
            if (match?.[1] === 'mermaid') {
              const chart = codeText(children).replace(/\n$/, '')
              const incomplete =
                unclosedMermaid !== null && node?.position?.start.line === unclosedMermaid
              return (
                <MermaidDiagram
                  chart={chart}
                  incomplete={incomplete}
                  key={`${incomplete}:${chart}`}
                />
              )
            }
            return (
              <code className={className} {...props}>
                {children}
              </code>
            )
          },
          pre({ children, node: _node, ...props }) {
            const first = Children.toArray(children)[0]
            if (
              isValidElement<{ className?: string }>(first) &&
              typeof first.props.className === 'string' &&
              first.props.className.includes('language-mermaid')
            ) {
              return <>{children}</>
            }
            return <pre {...props}>{children}</pre>
          },
          table({ children, node: _node, ...props }) {
            return (
              <div className="md-table-wrapper">
                <table {...props}>{children}</table>
              </div>
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

function codeText(children: ReactNode): string {
  if (typeof children === 'string') return children
  if (Array.isArray(children)) {
    return children.map((child) => (typeof child === 'string' ? child : '')).join('')
  }
  return ''
}

function detectCallout(children: ReactNode): null | {
  content: ReactNode
  label: string
  type: string
} {
  const fullText = textContent(children)
  const match = CALLOUT_RE.exec(fullText)
  if (!match?.[1]) return null
  const type = match[1].toLowerCase()
  const stripped = Children.toArray(stripFirstMarker(children)).filter((child) => {
    if (typeof child === 'string') return child.trim() !== ''
    if (isValidElement(child)) {
      if (textContent(child).trim() === '') return false
    }
    return true
  })
  return { content: stripped, label: CALLOUT_LABELS[type] ?? type, type }
}

function findUnclosedMermaidFence(content: string): null | number {
  let open: null | {
    language: string
    line: number
    marker: string
    markerLength: number
    quoteDepth: number
  } = null

  for (const [index, line] of content.split('\n').entries()) {
    const quotePrefix = /^(?: {0,3}>[ \t]?)+/.exec(line)?.[0] ?? ''
    const quoteDepth = (quotePrefix.match(/>/g) ?? []).length
    if (open !== null && quoteDepth < open.quoteDepth) open = null
    const nestedLine = line.slice(quotePrefix.length).replace(/^\s*(?:[-+*]|\d+[.)])\s+/, '')
    const fence = /^\s*(`{3,}|~{3,})(.*)$/.exec(nestedLine)
    if (open === null) {
      if (fence) {
        open = {
          language: fence[2].trim().split(/\s+/)[0] ?? '',
          line: index + 1,
          marker: fence[1][0],
          markerLength: fence[1].length,
          quoteDepth,
        }
      }
      continue
    }

    const closesFence =
      fence !== null &&
      fence[1][0] === open.marker &&
      fence[1].length >= open.markerLength &&
      fence[2].trim() === ''
    if (closesFence) {
      open = null
    }
  }

  return open?.language === 'mermaid' ? open.line : null
}

function MermaidDiagram({ chart, incomplete }: { chart: string; incomplete: boolean }) {
  const recognition = recognize(chart)
  const recognitionError = incomplete
    ? 'Incomplete Mermaid diagram'
    : recognition.ok
      ? null
      : recognition.error
  const renderText = recognition.ok && !incomplete ? recognition.renderText : null
  const [svg, setSvg] = useState<null | string>(null)
  const [error, setError] = useState<null | string>(null)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [copied, setCopied] = useResetAfterDelay(false, false, 1500)
  const { resolvedTheme } = useTheme()
  const id = useId()
  const renderCounter = useRef(0)

  const handleCopy = () => {
    if (!recognition.ok || incomplete) return
    void copyToClipboard(recognition.renderText, 'Diagram copied to clipboard').then(setCopied)
  }

  useEffect(() => {
    let isCancelled = false
    renderCounter.current += 1
    const currentRenderId = renderCounter.current
    const cleanId = id.replace(/:/g, '')
    const svgId = `mermaid-${cleanId}-${currentRenderId}`

    if (renderText === null) return

    const renderMermaid = async () => {
      try {
        const isDarkMode = resolvedTheme === 'dark'
        mermaid.initialize({
          securityLevel: 'loose',
          startOnLoad: false,
          ...getMermaidConfig(isDarkMode),
        })
        const { svg: renderedSvg } = await mermaid.render(svgId, renderText)
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
  }, [renderText, resolvedTheme, id])

  const displayError = recognitionError ?? error
  if (displayError) {
    return (
      <div className="border-destructive bg-destructive/10 text-destructive rounded-md border p-4 text-sm">
        <p className="font-semibold">Failed to render Mermaid diagram</p>
        <p className="mt-1 break-words opacity-80">{displayError}</p>
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
          <div className="absolute top-2 right-2 flex gap-1.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
            <button
              aria-label="Copy diagram source"
              className="bg-background/80 text-muted-foreground hover:text-foreground hover:bg-accent rounded-md border p-1.5 shadow-sm"
              onClick={handleCopy}
              type="button"
            >
              {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
            </button>
            <button
              aria-label="View diagram fullscreen"
              className="bg-background/80 text-muted-foreground hover:text-foreground hover:bg-accent rounded-md border p-1.5 shadow-sm"
              onClick={() => setIsFullscreen(true)}
              type="button"
            >
              <Maximize2 className="h-4 w-4" />
            </button>
          </div>
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

function stripDeep(element: ReactElement<{ children?: ReactNode }>): ReactNode {
  const kids = element.props.children
  if (typeof kids === 'string') return cloneElement(element, {}, kids.replace(CALLOUT_RE, ''))
  if (Array.isArray(kids)) {
    let done = false
    const next = kids.map((child) => {
      if (done) return child
      if (typeof child === 'string') {
        const replaced = child.replace(CALLOUT_RE, '')
        if (replaced !== child) done = true
        return replaced
      }
      if (isValidElement<{ children?: ReactNode }>(child) && CALLOUT_RE.test(textContent(child))) {
        done = true
        return stripDeep(child)
      }
      return child
    })
    return cloneElement(element, {}, next)
  }
  if (isValidElement<{ children?: ReactNode }>(kids)) {
    return cloneElement(element, {}, stripDeep(kids))
  }
  return element
}

function stripFirstMarker(value: ReactNode): ReactNode {
  if (typeof value === 'string') return value.replace(CALLOUT_RE, '')
  if (Array.isArray(value)) {
    let done = false
    return value.map((child) => {
      if (done) return child
      if (typeof child === 'string') {
        if (CALLOUT_RE.test(child)) {
          done = true
          return child.replace(CALLOUT_RE, '')
        }
        return child
      }
      if (isValidElement<{ children?: ReactNode }>(child) && CALLOUT_RE.test(textContent(child))) {
        done = true
        return stripDeep(child)
      }
      return child
    })
  }
  if (isValidElement<{ children?: ReactNode }>(value)) return stripDeep(value)
  return value
}

function textContent(value: ReactNode): string {
  if (value == null || typeof value === 'boolean') return ''
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return value.map(textContent).join('')
  if (isValidElement<{ children?: ReactNode }>(value)) return textContent(value.props.children)
  return ''
}
