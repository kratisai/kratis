import {
  ChevronsDownUp,
  ChevronsUpDown,
  FileCode2,
  GitPullRequest,
  Loader2,
  RefreshCw,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'

import type { ExecutionStatus } from '@/lib/execution-api'

import { Button } from '@/components/ui/button'
import { useDiffSummary } from '@/hooks/use-diff'
import { useIsMobile } from '@/hooks/use-is-mobile'
import { cn } from '@/lib/utils'
import { useDiffReviewStore } from '@/store/diff-review-store'

import { DiffBreadcrumb } from './diff-breadcrumb'
import { DiffFileCard } from './diff-file-card'
import { PublishModal } from './publish-modal'
import { SteeringPublishBar } from './steering-publish-bar'

interface ExecutionDiffTabProps {
  chatId: string
  executionId: string
  executionStatus?: ExecutionStatus
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void
  showSteeringBar?: boolean
}

export function ExecutionDiffTab({
  chatId,
  executionId,
  executionStatus,
  onScroll,
  showSteeringBar = false,
}: ExecutionDiffTabProps) {
  const { data: summary, isError, isLoading, refetch } = useDiffSummary(chatId, executionId)

  const [isAutoHidden, setIsAutoHidden] = useState(false)
  const [isPublishModalOpen, setIsPublishModalOpen] = useState(false)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const lastScrollTopRef = useRef(0)
  const isMobile = useIsMobile()

  const collapseAll = useDiffReviewStore((state) => state.collapseAll)
  const expandAll = useDiffReviewStore((state) => state.expandAll)
  const diffViewMode = useDiffReviewStore((state) => state.diffViewMode)
  const setDiffViewMode = useDiffReviewStore((state) => state.setDiffViewMode)

  // Scroll listener for auto-hide heuristics
  useEffect(() => {
    if (isMobile) {
      const handleWindowScroll = () => {
        const currentScrollY = window.scrollY
        const scrollable = document.documentElement
        const isScrollingDown = currentScrollY > lastScrollTopRef.current && currentScrollY > 50
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
    }

    const el = scrollContainerRef.current
    if (!el) return

    const handleScroll = () => {
      const currentScrollTop = el.scrollTop
      const isScrollingDown = currentScrollTop > lastScrollTopRef.current && currentScrollTop > 50
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

    el.addEventListener('scroll', handleScroll, { passive: true })
    return () => el.removeEventListener('scroll', handleScroll)
  }, [isMobile])

  const sortedFiles = useMemo(() => {
    if (!summary?.files) return []
    return [...summary.files].sort((a, b) => a.path.localeCompare(b.path))
  }, [summary?.files])

  const filePaths = useMemo(() => sortedFiles.map((f) => f.path), [sortedFiles])

  return (
    <div className="relative flex h-full flex-col" data-testid="execution-diff-tab">
      {/* Sticky breadcrumb pinned at top */}
      <DiffBreadcrumb />

      {/* Main Diff Content */}
      <div
        className="flex-1 overflow-visible pb-20 md:overflow-y-auto"
        onScroll={onScroll}
        ref={scrollContainerRef}
      >
        <div className="mx-auto space-y-2 p-2 sm:space-y-4 sm:p-4">
          {/* Diff Summary Top Header */}
          <div className="border-border/60 flex h-8 items-center justify-between gap-2 border-b pb-2">
            <div className="flex items-center gap-2 truncate">
              <span className="hidden text-xs font-semibold sm:inline">Working Tree</span>
              {summary?.files && (
                <div className="flex items-center gap-1.5 font-mono text-xs font-medium">
                  <span className="text-muted-foreground">
                    ({summary.files.length} {summary.files.length === 1 ? 'file' : 'files'})
                  </span>
                  <span className="text-emerald-600 dark:text-emerald-400">
                    +{summary.totalAdditions}
                  </span>
                  <span className="text-rose-600 dark:text-rose-400">
                    -{summary.totalDeletions}
                  </span>
                </div>
              )}
            </div>

            {/* Actions: Collapse / Expand / Side-by-side Toggle */}
            <div className="flex items-center gap-1">
              <Button
                aria-label="Collapse All"
                className="text-muted-foreground hover:text-foreground h-7 px-1.5 text-xs sm:px-2"
                onClick={() => {
                  if (filePaths.length > 0) {
                    collapseAll(filePaths)
                  }
                }}
                size="sm"
                variant="ghost"
              >
                <ChevronsDownUp className="h-3.5 w-3.5 sm:mr-1" />
                <span className="hidden sm:inline">Collapse All</span>
              </Button>

              <Button
                aria-label="Expand All"
                className="text-muted-foreground hover:text-foreground h-7 px-1.5 text-xs sm:px-2"
                onClick={() => {
                  if (filePaths.length > 0) {
                    expandAll(filePaths)
                  }
                }}
                size="sm"
                variant="ghost"
              >
                <ChevronsUpDown className="h-3.5 w-3.5 sm:mr-1" />
                <span className="hidden sm:inline">Expand All</span>
              </Button>

              {/* Diff View Mode Segmented Control (Desktop / Tablet Only) */}
              <div className="bg-muted hidden items-center rounded-md p-0.5 text-xs md:flex">
                <Button
                  className={cn(
                    'h-6 px-2 text-xs',
                    diffViewMode === 'unified' && 'bg-background text-foreground shadow-xs',
                  )}
                  onClick={() => setDiffViewMode('unified')}
                  size="sm"
                  variant="ghost"
                >
                  Unified
                </Button>
                <Button
                  className={cn(
                    'h-6 px-2 text-xs',
                    diffViewMode === 'split' && 'bg-background text-foreground shadow-xs',
                  )}
                  onClick={() => setDiffViewMode('split')}
                  size="sm"
                  variant="ghost"
                >
                  Split
                </Button>
              </div>

              <Button
                aria-label="Publish changes"
                className="text-muted-foreground hover:text-foreground h-7 px-1.5 text-xs sm:px-2"
                onClick={() => setIsPublishModalOpen(true)}
                size="sm"
                variant="ghost"
              >
                <GitPullRequest className="h-3.5 w-3.5 sm:mr-1" />
                <span className="hidden sm:inline">Publish</span>
              </Button>

              <Button
                aria-label="Refresh diff"
                className="text-muted-foreground hover:text-foreground h-7 w-7 p-0"
                disabled={isLoading}
                onClick={() => void refetch()}
                size="sm"
                variant="ghost"
              >
                <RefreshCw className={cn('h-3.5 w-3.5', isLoading && 'animate-spin')} />
              </Button>
            </div>
          </div>

          {/* Loading Skeleton */}
          {isLoading && (
            <div className="flex h-64 flex-col items-center justify-center gap-3">
              <Loader2 className="text-primary h-6 w-6 animate-spin" />
              <p className="text-muted-foreground text-xs">Inspecting workspace changes...</p>
            </div>
          )}

          {/* Error State */}
          {isError && (
            <div className="border-destructive/40 bg-destructive/5 text-destructive space-y-2 rounded-lg border p-6 text-center text-xs">
              <p className="font-semibold">Unable to load changes</p>
              <p className="text-muted-foreground">
                The sandbox environment may still be preparing or starting up.
              </p>
              <Button className="mt-2 h-7 text-xs" onClick={() => void refetch()} size="sm">
                Try Again
              </Button>
            </div>
          )}

          {/* Empty State */}
          {summary?.files && summary.files.length === 0 && (
            <div className="text-muted-foreground flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed py-16">
              <FileCode2 className="text-muted-foreground/40 h-8 w-8" />
              <p className="text-xs">No file changes in working tree yet.</p>
            </div>
          )}

          {/* Integrated File Diffs List */}
          {sortedFiles.length > 0 && (
            <div className="border-border/70 divide-border/60 bg-card mb-20 divide-y overflow-hidden rounded-lg border shadow-xs">
              {sortedFiles.map((file) => (
                <DiffFileCard
                  chatId={chatId}
                  executionId={executionId}
                  file={file}
                  key={file.path}
                />
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Dynamic Steering & Publish Bottom Bar (When Rendered Standalone) */}
      {showSteeringBar && (
        <SteeringPublishBar
          chatId={chatId}
          executionId={executionId}
          executionStatus={executionStatus}
          isAutoHidden={isAutoHidden}
        />
      )}

      {/* Publish Dialog Modal */}
      <PublishModal
        chatId={chatId}
        executionId={executionId}
        executionStatus={executionStatus}
        onOpenChange={setIsPublishModalOpen}
        open={isPublishModalOpen}
      />
    </div>
  )
}
