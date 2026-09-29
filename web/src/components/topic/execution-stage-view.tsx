import { useSearch } from '@tanstack/react-router'
import { Download, MoreHorizontal, Square, Terminal } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'

import type { Activity } from '@/types/execution-activity-types'

import { ExecutionDiffTab } from '@/components/session/diff/execution-diff-tab'
import { SteeringPublishBar } from '@/components/session/diff/steering-publish-bar'
import { ExecutionActivityLog } from '@/components/session/execution-activity-log'
import { ExecutionUsageSummary } from '@/components/session/execution-usage-summary'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useChatExecutions } from '@/hooks/use-executions'
import { useIsMobile } from '@/hooks/use-is-mobile'
import { downloadActivityLog } from '@/lib/activity-log-export'
import { terminateExecution } from '@/lib/execution-api'
import { useActivityStore } from '@/store/activity-store'
import { useDiffReviewStore } from '@/store/diff-review-store'
import { useExecutionStore } from '@/store/execution-store'

const EMPTY_ACTIVITIES: Activity[] = []

interface ExecutionStageViewProps {
  chatId: string
  executionId: string
  tab?: ExecutionSubTab
}

type ExecutionSubTab = 'activity' | 'changes'

export function ExecutionStageView({ chatId, executionId, tab }: ExecutionStageViewProps) {
  const search: { tab?: string } = useSearch({ strict: false })
  const activeSubTab: ExecutionSubTab = tab ?? (search.tab === 'changes' ? 'changes' : 'activity')

  const [isAutoHidden, setIsAutoHidden] = useState(false)
  const lastScrollTopRef = useRef(0)
  const isMobile = useIsMobile()

  const diffViewMode = useDiffReviewStore((state) => state.diffViewMode)
  const { data: executions = [] } = useChatExecutions(chatId)
  const execution = executions.find((e) => e.id === executionId)

  const terminalOpen = useExecutionStore((state) => state.terminalOpen)
  const setTerminalOpen = useExecutionStore((state) => state.setTerminalOpen)
  const replayingExecutionId = useExecutionStore((state) => state.replayingExecutionId)
  const replayActivities = useExecutionStore((state) => state.replayActivities)

  const activities = useActivityStore((state) =>
    executionId ? (state.activitiesByExecution[executionId] ?? EMPTY_ACTIVITIES) : EMPTY_ACTIVITIES,
  )

  const replayedRef = useRef<null | string>(null)

  useEffect(() => {
    if (!executionId) return
    if (replayedRef.current === executionId) return
    if (!execution || execution.status === 'RUNNING') return
    if (replayingExecutionId === executionId) return
    if (activities.length > 0) return
    replayedRef.current = executionId
    replayActivities(executionId)
  }, [activities.length, execution, executionId, replayActivities, replayingExecutionId])

  useEffect(() => {
    if (!isMobile) return

    const handleWindowScroll = () => {
      const currentScrollY = window.scrollY
      const scrollable = document.documentElement
      const isScrollingDown = currentScrollY > lastScrollTopRef.current && currentScrollY > 40
      const isAtBottom = currentScrollY + window.innerHeight >= scrollable.scrollHeight - 20
      const isAtTop = currentScrollY < 20

      if (isAtTop || isAtBottom) {
        setIsAutoHidden(false)
      } else if (isScrollingDown) {
        setIsAutoHidden(true)
      } else {
        setIsAutoHidden(false)
      }

      lastScrollTopRef.current = currentScrollY
    }

    window.addEventListener('scroll', handleWindowScroll, { passive: true })
    return () => window.removeEventListener('scroll', handleWindowScroll)
  }, [isMobile])

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    if (isMobile) return
    const el = e.currentTarget
    const currentScrollTop = el.scrollTop
    const isScrollingDown = currentScrollTop > lastScrollTopRef.current && currentScrollTop > 40
    const isAtBottom = currentScrollTop + el.clientHeight >= el.scrollHeight - 20
    const isAtTop = currentScrollTop < 20

    if (isAtTop || isAtBottom) {
      setIsAutoHidden(false)
    } else if (isScrollingDown) {
      setIsAutoHidden(true)
    } else {
      setIsAutoHidden(false)
    }

    lastScrollTopRef.current = currentScrollTop
  }

  const handleTerminate = async () => {
    if (!execution) return
    try {
      await terminateExecution(chatId, execution.id)
      toast.success('Terminate request sent')
    } catch {
      toast.error('Failed to terminate execution')
    }
  }

  const hasActiveExecution = execution?.status === 'RUNNING' || execution?.status === 'IDLE'

  return (
    <div
      className="flex h-full w-full min-w-0 flex-col md:overflow-hidden"
      data-testid="execution-stage-view"
    >
      <div className="border-border/60 bg-muted/20 hidden h-9.5 items-center justify-end border-b px-3 backdrop-blur-xs select-none md:flex">
        <div className="flex items-center gap-1.5 sm:gap-2">
          <div className="hidden sm:block">
            <ExecutionUsageSummary chatId={chatId} executionId={executionId} />
          </div>

          {hasActiveExecution && (
            <Button
              aria-label={terminalOpen ? 'Hide terminal' : 'Show terminal'}
              className="text-muted-foreground hover:text-foreground h-7 w-7"
              onClick={() => setTerminalOpen(!terminalOpen)}
              size="icon"
              variant="ghost"
            >
              <Terminal className="h-3.5 w-3.5" />
            </Button>
          )}

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                aria-label="Activity log options"
                className="text-muted-foreground hover:text-foreground h-7 w-7"
                size="icon"
                variant="ghost"
              >
                <MoreHorizontal className="h-3.5 w-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
              {hasActiveExecution && (
                <DropdownMenuItem onClick={() => setTerminalOpen(!terminalOpen)}>
                  <Terminal className="mr-2 h-3.5 w-3.5" />
                  {terminalOpen ? 'Hide terminal' : 'Show terminal'}
                </DropdownMenuItem>
              )}
              <DropdownMenuItem
                onClick={() => {
                  try {
                    downloadActivityLog(activities, executionId)
                    toast.success('Activity log downloaded')
                  } catch {
                    toast.error('Failed to download activity log')
                  }
                }}
              >
                <Download className="mr-2 h-3.5 w-3.5" />
                Download
              </DropdownMenuItem>
              {hasActiveExecution && (
                <>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem
                    className="text-destructive focus:text-destructive"
                    onClick={() => {
                      void handleTerminate()
                    }}
                  >
                    <Square className="mr-2 h-3.5 w-3.5 fill-current" />
                    Terminate
                  </DropdownMenuItem>
                </>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      {/* Main Tab Content */}
      <div className="relative flex min-h-0 flex-1 overflow-visible md:overflow-hidden">
        {activeSubTab === 'activity' ? (
          <div
            className="min-w-0 flex-1 [scroll-padding-bottom:5rem] overflow-visible pb-20 md:overflow-auto"
            onScroll={handleScroll}
          >
            <div className="mx-auto w-full max-w-4xl min-w-0 px-2 sm:px-4">
              <ExecutionActivityLog executionId={executionId} />
            </div>
          </div>
        ) : (
          /* Changes is active */
          <div className="flex min-h-0 min-w-0 flex-1 overflow-visible md:overflow-hidden">
            {/* If Unified diff mode on widescreen, show 50% Activity Log on the left */}
            {diffViewMode === 'unified' && (
              <div
                className="border-border/60 hidden min-w-0 flex-1 [scroll-padding-bottom:5rem] flex-col overflow-auto border-r pb-20 xl:flex xl:max-w-[50%]"
                onScroll={handleScroll}
              >
                <div className="w-full px-2 sm:px-4">
                  <ExecutionActivityLog executionId={executionId} />
                </div>
              </div>
            )}

            {/* Diff pane: 50% in unified mode on widescreen, 100% in split mode and on small-desktop/mobile */}
            <div className="min-w-0 flex-1 overflow-visible md:overflow-hidden">
              <ExecutionDiffTab
                chatId={chatId}
                executionId={executionId}
                executionStatus={execution?.status}
                onScroll={handleScroll}
                showSteeringBar={false}
              />
            </div>
          </div>
        )}

        {/* Continuous Steering & Publish Bar */}
        <SteeringPublishBar
          chatId={chatId}
          executionId={executionId}
          executionStatus={execution?.status}
          isAutoHidden={isAutoHidden}
        />
      </div>
    </div>
  )
}
