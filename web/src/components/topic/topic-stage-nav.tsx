import { useNavigate, useSearch } from '@tanstack/react-router'
import {
  AlertCircle,
  Bot,
  CheckCircle2,
  ChevronDown,
  GitCompare,
  Loader2,
  PenTool,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import type { ExecutionStatus, SandboxExecutionDto } from '@/lib/execution-api'
import type { CanvasDocument } from '@/types/canvas-types'

import { AgentBrandIcon } from '@/components/session/agent-brand-icon'
import { harnessDisplayName } from '@/components/session/execution-harness-badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useDiffSummary } from '@/hooks/use-diff'
import { formatRelativeTime, formatSpend } from '@/lib/format'
import { cn } from '@/lib/utils'
import { useCanvasStore } from '@/store/canvas-store'
import { useWebSocketStore } from '@/store/websocket-store'

const EMPTY_UNREAD: string[] = []

interface TopicStageNavProps {
  activeDocId?: null | string
  activeExecutionId?: null | string
  canvasCount: number
  canvasDocuments?: CanvasDocument[]
  chatId: string
  currentStage: 'design' | 'execution'
  executions: SandboxExecutionDto[]
}

export function TopicStageNav({
  activeDocId,
  activeExecutionId,
  canvasCount,
  canvasDocuments = [],
  chatId,
  currentStage,
  executions,
}: TopicStageNavProps) {
  const navigate = useNavigate()
  const search: { tab?: string } = useSearch({ strict: false })
  const isConnected = useWebSocketStore((state) => state.isConnected)
  const unreadDocIds = useCanvasStore((state) =>
    chatId ? (state.unreadCanvasDocIds[chatId] ?? EMPTY_UNREAD) : EMPTY_UNREAD,
  )
  const clearCanvasActivity = useCanvasStore((state) => state.clearCanvasActivity)
  const containerRef = useRef<HTMLDivElement>(null)
  const [visibleCount, setVisibleCount] = useState(executions.length)
  const [, setLiveTick] = useState(0)

  const hasUnread = unreadDocIds.length > 0

  useEffect(() => {
    if (activeDocId && unreadDocIds.includes(activeDocId)) {
      clearCanvasActivity(chatId, activeDocId)
    }
  }, [activeDocId, chatId, clearCanvasActivity, unreadDocIds])

  const { data: diffSummary } = useDiffSummary(
    currentStage === 'execution' ? chatId : null,
    currentStage === 'execution' ? activeExecutionId : null,
  )
  const fileCount = diffSummary?.files.length ?? 0
  const activeSubTab = search.tab === 'changes' ? 'changes' : 'activity'

  const hasRunning = executions.some((e) => e.status === 'RUNNING')
  useEffect(() => {
    if (!hasRunning) return
    const interval = setInterval(() => setLiveTick((t) => t + 1), 1000)
    return () => clearInterval(interval)
  }, [hasRunning])

  const orderedExecutions = executions
    .map((exec, idx) => ({ ...exec, runNumber: idx + 1 }))
    .reverse()

  useEffect(() => {
    const updateVisibleCount = () => {
      if (!containerRef.current || containerRef.current.offsetWidth === 0) {
        setVisibleCount(executions.length)
        return
      }
      const width = containerRef.current.offsetWidth
      const availableForRuns = width - 420
      const maxPossible = Math.max(1, Math.floor(availableForRuns / 240))
      setVisibleCount(Math.min(executions.length, maxPossible))
    }

    updateVisibleCount()
    window.addEventListener('resize', updateVisibleCount)
    return () => window.removeEventListener('resize', updateVisibleCount)
  }, [executions.length])

  const visibleExecutions = orderedExecutions.slice(0, visibleCount)
  const overflowExecutions = orderedExecutions.slice(visibleCount)

  const handleSelectChat = () => {
    void navigate({
      params: { id: chatId },
      to: '/chats/$id',
    })
  }

  const handleSelectCanvasDoc = (docId: string) => {
    clearCanvasActivity(chatId, docId)
    void navigate({
      params: { docId, id: chatId },
      to: '/chats/$id/canvas/$docId',
    })
  }

  const handleSelectActivity = (execId: string) => {
    void navigate({
      params: { executionId: execId, id: chatId },
      search: {},
      to: '/chats/$id/executions/$executionId',
    })
  }

  const handleSelectChanges = (execId: string) => {
    void navigate({
      params: { executionId: execId, id: chatId },
      search: { tab: 'changes' },
      to: '/chats/$id/executions/$executionId',
    })
  }

  return (
    <div
      className="border-border/60 bg-muted/20 hidden h-15 w-full items-center gap-2 border-b px-3 backdrop-blur-xs select-none md:flex"
      data-testid="topic-stage-nav"
      ref={containerRef}
    >
      <div
        aria-label="Design and Plan Stage"
        className={cn(
          'flex h-12 min-w-[160px] cursor-pointer flex-col justify-center rounded-lg border px-3 py-1.5 text-left transition-all',
          currentStage === 'design'
            ? 'border-border/90 bg-background text-foreground ring-border/80 shadow-xs ring-1'
            : 'border-border/40 text-muted-foreground hover:border-primary/40 hover:bg-muted/40 hover:text-foreground',
        )}
        data-testid="stage-card-design"
        onClick={handleSelectChat}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            handleSelectChat()
          }
        }}
        role="button"
        tabIndex={0}
      >
        <div className="flex items-center gap-1.5 text-xs font-medium">
          <PenTool className="text-primary h-3.5 w-3.5" />
          <span>Design & Plan</span>
        </div>
        <div className="flex items-center gap-1 text-[11px]">
          <span
            className={cn(
              'rounded px-1 py-0.5 font-medium transition-colors',
              currentStage === 'design' && !activeDocId
                ? 'bg-background ring-border/80 text-foreground ring-1'
                : 'text-muted-foreground',
            )}
          >
            Chat
          </span>
          {canvasCount > 0 && (
            <>
              <span className="text-muted-foreground/60">|</span>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    aria-label="Canvas documents"
                    className={cn(
                      'flex cursor-pointer items-center gap-1 rounded-md border border-transparent px-1.5 py-0.5 font-medium transition-all',
                      currentStage === 'design' && activeDocId
                        ? 'bg-background text-primary ring-border/80 shadow-xs ring-1'
                        : 'bg-muted/40 text-primary hover:bg-background hover:border-primary hover:shadow-sm',
                      hasUnread && 'animate-pulse border-emerald-500/50',
                    )}
                    onClick={(e) => e.stopPropagation()}
                    type="button"
                  >
                    {hasUnread && (
                      <span
                        className="relative flex h-2 w-2 shrink-0"
                        data-testid="canvas-pulse-indicator"
                      >
                        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                        <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
                      </span>
                    )}
                    <span>
                      {canvasCount} {canvasCount === 1 ? 'doc' : 'docs'}
                    </span>
                    <ChevronDown className="h-3 w-3 opacity-60" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent
                  align="start"
                  className="w-56"
                  onClick={(e) => e.stopPropagation()}
                >
                  {canvasDocuments.map((doc) => {
                    const isUnread = unreadDocIds.includes(doc.documentId)
                    return (
                      <DropdownMenuItem
                        key={doc.documentId}
                        onClick={() => handleSelectCanvasDoc(doc.documentId)}
                      >
                        <span className="flex-1 truncate">{doc.title}</span>
                        {isUnread && (
                          <span
                            className="ml-2 rounded-full bg-emerald-500/15 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-600 dark:text-emerald-400"
                            data-testid={`canvas-unread-badge-${doc.documentId}`}
                          >
                            updated
                          </span>
                        )}
                        {activeDocId === doc.documentId && (
                          <span className="bg-primary/15 text-primary ml-2 rounded-full px-1.5 py-0.5 text-[10px] font-semibold">
                            active
                          </span>
                        )}
                      </DropdownMenuItem>
                    )
                  })}
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          )}
        </div>
      </div>

      {executions.length > 0 && <div className="bg-border/60 h-6 w-px" />}

      {visibleExecutions.map((exec) => {
        const isSelected = currentStage === 'execution' && activeExecutionId === exec.id
        const launchedStr = exec.startedAt ? formatRelativeTime(exec.startedAt) : ''
        const durationStr = formatDuration(exec.startedAt, exec.completedAt ?? undefined)
        const harnessName = harnessDisplayName(exec.harness ?? '')

        return (
          <div
            className={cn(
              'flex h-12 overflow-hidden rounded-lg border transition-all',
              isSelected
                ? 'border-border/90 bg-background ring-border/80 shadow-xs ring-1'
                : 'border-border/40 hover:border-primary/40',
            )}
            data-testid={`stage-card-run-${exec.id}`}
            key={exec.id}
            title={`${harnessName} · Launched ${new Date(exec.startedAt).toLocaleString()}${
              durationStr ? ` · ran ${durationStr}` : ''
            }`}
          >
            <button
              aria-label={`${harnessName} activity`}
              className={cn(
                'flex min-w-0 flex-1 cursor-pointer flex-col justify-center px-3 py-1.5 text-left transition-colors',
                isSelected && activeSubTab === 'activity'
                  ? 'bg-background text-foreground ring-border/40 ring-1'
                  : 'text-muted-foreground hover:bg-muted/30 hover:text-foreground',
              )}
              data-testid={`stage-card-run-${exec.id}-activity`}
              onClick={() => handleSelectActivity(exec.id)}
              type="button"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="flex items-center gap-1.5 truncate font-mono text-[10px] tabular-nums">
                  <ExecutionStatusIcon status={exec.status} />
                  <span className="truncate">{launchedStr}</span>
                </span>
                <span className="font-mono text-[10px] tabular-nums">
                  {formatSpend(exec.totalSpend)}
                </span>
              </div>
              <div className="flex items-center justify-between gap-2">
                <span className="flex min-w-0 items-center gap-1.5 truncate text-xs font-medium">
                  <AgentBrandIcon className="h-3.5 w-3.5 shrink-0" harness={exec.harness} />
                  <span className="truncate">{harnessName}</span>
                </span>
                <span className="font-mono text-[10px] tabular-nums">{durationStr}</span>
              </div>
            </button>
            <button
              aria-label={`${harnessName} changes`}
              className={cn(
                'flex w-[64px] shrink-0 cursor-pointer flex-col items-center justify-center gap-0.5 border-l px-2 text-[10px] font-medium transition-colors',
                isSelected && activeSubTab === 'changes'
                  ? 'bg-background text-foreground ring-border/40 ring-1'
                  : 'bg-muted/20 text-muted-foreground hover:bg-muted/40 hover:text-foreground',
              )}
              data-testid={`stage-card-run-${exec.id}-changes`}
              onClick={() => handleSelectChanges(exec.id)}
              type="button"
            >
              <GitCompare className="text-primary h-3.5 w-3.5" />
              <span>changes</span>
              {isSelected && fileCount > 0 && (
                <span className="bg-primary/15 text-primary rounded-full px-1 py-0.5 text-[9px] leading-none font-semibold">
                  {fileCount}
                </span>
              )}
            </button>
          </div>
        )
      })}

      {overflowExecutions.length > 0 && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              aria-label="More execution runs"
              className="text-muted-foreground hover:text-foreground border-border/40 hover:border-primary/40 h-11 gap-1 rounded-lg border px-2.5 text-xs font-medium"
              size="sm"
              variant="ghost"
            >
              <span>+{overflowExecutions.length} more</span>
              <ChevronDown className="h-3 w-3 opacity-60" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-60">
            {overflowExecutions.map((exec) => {
              const durationStr = formatDuration(exec.startedAt, exec.completedAt ?? undefined)
              const launchedStr = exec.startedAt ? formatRelativeTime(exec.startedAt) : ''
              const isSelected = currentStage === 'execution' && activeExecutionId === exec.id

              return (
                <DropdownMenuItem
                  className={cn(
                    'flex items-center justify-between py-1.5 text-xs',
                    isSelected && 'bg-muted font-medium',
                  )}
                  key={exec.id}
                  onClick={() => handleSelectActivity(exec.id)}
                >
                  <div className="flex flex-col gap-0.5">
                    <div className="flex items-center gap-1.5 font-medium">
                      <ExecutionStatusIcon status={exec.status} />
                      <span>Run {exec.runNumber}</span>
                    </div>
                    <div className="text-muted-foreground flex items-center gap-1 text-[10px]">
                      <AgentBrandIcon className="h-2.5 w-2.5" harness={exec.harness} />
                      <span>{harnessDisplayName(exec.harness ?? '')}</span>
                      {launchedStr && (
                        <>
                          <span className="text-muted-foreground/60">·</span>
                          <span className="font-mono">{launchedStr}</span>
                        </>
                      )}
                    </div>
                  </div>
                  <div className="flex flex-col items-end gap-0.5">
                    {durationStr && (
                      <span className="text-muted-foreground font-mono text-[10px]">
                        {durationStr}
                      </span>
                    )}
                    <span className="text-muted-foreground font-mono text-[10px]">
                      {formatSpend(exec.totalSpend)}
                    </span>
                  </div>
                </DropdownMenuItem>
              )
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      )}

      <div className="ml-auto hidden items-center gap-1.5 sm:flex">
        <div
          className={cn(
            'h-2 w-2 rounded-full',
            isConnected ? 'bg-emerald-500 shadow-xs shadow-emerald-500/50' : 'bg-zinc-400',
          )}
        />
        <span className="text-muted-foreground font-sans text-[11px]">
          {isConnected ? 'Connected' : 'Disconnected'}
        </span>
      </div>
    </div>
  )
}

function ExecutionStatusIcon({ status }: { status: ExecutionStatus }) {
  switch (status) {
    case 'COMPLETED':
    case 'IDLE':
      return <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
    case 'FAILED':
      return <AlertCircle className="text-destructive h-3.5 w-3.5" />
    case 'RUNNING':
      return <Loader2 className="h-3.5 w-3.5 animate-spin text-emerald-500" />
    default:
      return <Bot className="text-muted-foreground h-3.5 w-3.5" />
  }
}

function formatDuration(startTime?: string, endTime?: string): string {
  if (!startTime) return ''
  const start = new Date(startTime).getTime()
  const end = endTime ? new Date(endTime).getTime() : Date.now()
  const totalSeconds = Math.max(0, Math.floor((end - start) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes === 0) return `${seconds}s`
  return `${minutes}m ${seconds}s`
}
