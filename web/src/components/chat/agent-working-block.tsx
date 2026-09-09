import { AlertCircle, ChevronDown, ChevronRight } from 'lucide-react'
import { useState } from 'react'

import type { WorkingItem } from '@/types/telemetry-types'

import { MarkdownMessage } from '@/components/chat/markdown-message'
import { cn } from '@/lib/utils'

interface AgentWorkingBlockProps {
  endTime: null | number
  isStreaming: boolean
  items: WorkingItem[]
  startTime: null | number
}

export function AgentWorkingBlock({
  endTime,
  isStreaming,
  items,
  startTime,
}: AgentWorkingBlockProps) {
  const [manuallyExpanded, setManuallyExpanded] = useState(false)

  if (items.length === 0) return null

  const isExpanded = isStreaming || manuallyExpanded
  const duration = formatDuration(endTime, startTime)

  return (
    <div className="border-border bg-muted/30 overflow-x-auto rounded-lg border">
      {isStreaming ? (
        <div className="text-muted-foreground flex items-center gap-2 px-3 py-2 text-xs font-medium">
          <span>Agent working...</span>
        </div>
      ) : (
        <button
          className="text-muted-foreground hover:bg-muted/50 flex w-full items-center gap-2 px-3 py-2 text-xs font-medium transition-colors"
          onClick={() => setManuallyExpanded((expanded) => !expanded)}
          type="button"
        >
          {isExpanded ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          <span>Agent worked for {duration}</span>
        </button>
      )}
      <div
        className="grid transition-[grid-template-rows] duration-300 ease-in-out"
        data-testid="agent-working-items"
        style={{ gridTemplateRows: isExpanded ? '1fr' : '0fr' }}
      >
        <div className="min-h-0 overflow-hidden">
          <div
            className={cn('space-y-1.5 px-3 pb-3', !isStreaming && 'border-border border-t pt-2')}
          >
            {items.map((item, index) => (
              <WorkingItemRow item={item} key={index} />
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

function formatDuration(endMs: null | number, startMs: null | number): string {
  if (!startMs || !endMs) return '0:00'
  const durationMs = endMs - startMs
  const totalSeconds = Math.floor(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

function WorkingItemRow({ item }: { item: WorkingItem }) {
  if (item.type === 'thought') {
    return (
      <div className="text-muted-foreground flex items-start gap-2 text-xs">
        <span className="mt-0.5">•</span>
        <div
          className={
            '**:text-muted-foreground min-w-0 flex-1 ' +
            '[&_li]:my-0 [&_ol]:my-1 [&_p]:my-0 [&_pre]:my-1 [&_ul]:my-1'
          }
        >
          <MarkdownMessage content={item.text} />
        </div>
      </div>
    )
  }

  return (
    <div className="flex items-start gap-2 text-xs">
      {item.status === 'running' && <span className="text-primary mt-0.5 animate-pulse">●</span>}
      {item.status === 'complete' && <span className="mt-0.5 text-green-500">✓</span>}
      {item.status === 'error' && (
        <AlertCircle
          aria-label="Tool failed"
          className="mt-0.5 h-3 w-3 shrink-0 text-amber-500 dark:text-amber-400"
        />
      )}
      <div className="min-w-0 flex-1">
        <span className="text-muted-foreground">{item.thought}</span>
        {item.status === 'error' &&
          (item.errorMessage ? (
            <details className="ml-1.5 inline align-baseline" data-testid="tool-error-details">
              <summary className="inline cursor-pointer text-amber-600 select-none hover:underline dark:text-zinc-400 dark:underline dark:decoration-zinc-500/70 dark:underline-offset-2 dark:hover:text-zinc-200">
                (failed)
              </summary>
              <div className="text-muted-foreground mt-1 font-mono text-[11px] break-words whitespace-pre-wrap">
                {item.errorMessage}
              </div>
            </details>
          ) : (
            <span className="ml-1.5 text-amber-600 dark:text-zinc-400">(failed)</span>
          ))}
      </div>
    </div>
  )
}
