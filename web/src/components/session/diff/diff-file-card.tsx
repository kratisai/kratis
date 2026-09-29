import {
  ChevronDown,
  ChevronRight,
  FileCode,
  FileDiff,
  FileMinus,
  FilePlus,
  Loader2,
} from 'lucide-react'

import type { DiffCommentDraft, DiffSummaryFileDto } from '@/types/diff-types'

import { Button } from '@/components/ui/button'
import { useFileDiff } from '@/hooks/use-diff'
import { cn } from '@/lib/utils'
import { useDiffReviewStore } from '@/store/diff-review-store'

import { DiffHunkViewer } from './diff-hunk-viewer'

const EMPTY_COMMENTS: DiffCommentDraft[] = []

interface DiffFileCardProps {
  chatId: string
  executionId: string
  file: DiffSummaryFileDto
}

export function DiffFileCard({ chatId, executionId, file }: DiffFileCardProps) {
  const isCollapsed = useDiffReviewStore((state) => state.collapsedFiles[file.path] ?? true)
  const toggleFileCollapsed = useDiffReviewStore((state) => state.toggleFileCollapsed)
  const draftComments = useDiffReviewStore(
    (state) => state.draftComments[executionId] ?? EMPTY_COMMENTS,
  )
  const fileComments = draftComments.filter((c) => c.path === file.path)

  const {
    data: fileDiff,
    isError,
    isLoading,
  } = useFileDiff(chatId, executionId, file.path, !isCollapsed)

  const lastSlashIndex = file.path.lastIndexOf('/')
  const dirPath = lastSlashIndex !== -1 ? file.path.slice(0, lastSlashIndex) : ''
  const fileName = lastSlashIndex !== -1 ? file.path.slice(lastSlashIndex + 1) : file.path

  const getStatusIcon = () => {
    switch (file.status) {
      case 'ADDED':
        return <FilePlus className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
      case 'DELETED':
        return <FileMinus className="h-3.5 w-3.5 shrink-0 text-rose-500" />
      case 'RENAMED':
        return <FileDiff className="h-3.5 w-3.5 shrink-0 text-amber-500" />
      default:
        return <FileCode className="h-3.5 w-3.5 shrink-0 text-blue-500" />
    }
  }

  return (
    <div
      className={cn('transition-colors', isCollapsed ? 'opacity-90' : 'opacity-100')}
      data-testid={`diff-file-${file.path}`}
    >
      {/* File Card Header */}
      <div
        className="bg-muted/30 hover:bg-muted/60 flex cursor-pointer items-center justify-between gap-0 px-0 py-2 text-xs transition-colors select-none"
        onClick={() => toggleFileCollapsed(file.path)}
      >
        <div className="flex min-w-0 flex-1 items-center gap-1">
          <Button
            aria-label={isCollapsed ? 'Expand file diff' : 'Collapse file diff'}
            className="text-muted-foreground h-4 w-4 shrink-0"
            size="icon"
            variant="ghost"
          >
            {isCollapsed ? (
              <ChevronRight className="h-3.5 w-3.5" />
            ) : (
              <ChevronDown className="h-3.5 w-3.5" />
            )}
          </Button>

          {getStatusIcon()}

          <div
            className="flex min-w-0 items-center font-mono text-xs font-medium"
            title={file.path}
          >
            {dirPath && (
              <>
                <span className="text-muted-foreground truncate">{dirPath}</span>
                <span className="text-muted-foreground/60 shrink-0">/</span>
              </>
            )}
            <span className="text-foreground shrink-0">{fileName}</span>
          </div>

          {fileComments.length > 0 && (
            <span className="bg-primary/15 text-primary py-0.2 rounded-full px-1.5 text-[10px] font-semibold">
              {fileComments.length}
            </span>
          )}
        </div>

        {/* Change stats */}
        <div className="flex shrink-0 items-center gap-1.5 px-1 font-mono text-[9px] font-medium">
          {file.additions > 0 && (
            <span className="text-emerald-600 dark:text-emerald-400">+{file.additions}</span>
          )}
          {file.deletions > 0 && (
            <span className="text-rose-600 dark:text-rose-400">-{file.deletions}</span>
          )}
        </div>
      </div>

      {/* File Diff Body */}
      {!isCollapsed && (
        <div className="border-border/40 bg-card border-t">
          {isLoading && (
            <div className="text-muted-foreground flex items-center justify-center gap-2 py-6 text-xs">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              <span>Loading file diff...</span>
            </div>
          )}

          {isError && (
            <div className="text-destructive py-4 text-center text-xs">
              Failed to load diff for this file.
            </div>
          )}

          {fileDiff && (
            <DiffHunkViewer
              chatId={chatId}
              comments={fileComments}
              executionId={executionId}
              patch={fileDiff.patch}
              path={file.path}
              totalLines={fileDiff.totalLines}
            />
          )}
        </div>
      )}
    </div>
  )
}
