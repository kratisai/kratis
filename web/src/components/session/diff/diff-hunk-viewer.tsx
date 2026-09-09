import { DiffFile, DiffModeEnum, DiffView, type SplitSide } from '@git-diff-view/react'
import '@git-diff-view/react/styles/diff-view.css'
import langMap from 'lang-map'
import { ChevronDown, ChevronUp, Loader2 } from 'lucide-react'
import { useTheme } from 'next-themes'
import { Fragment, useEffect, useMemo, useState } from 'react'

import type { DiffCommentDraft } from '@/types/diff-types'

import { Button } from '@/components/ui/button'
import { useIsMobile } from '@/hooks/use-is-mobile'
import { fetchFileSlice } from '@/lib/diff-api'
import { useDiffReviewStore } from '@/store/diff-review-store'

import { DiffInlineComment } from './diff-inline-comment'

interface CommentExtendData {
  data: { comments: DiffCommentDraft[] }
}

interface DiffHunkViewerProps {
  chatId: string
  comments: DiffCommentDraft[]
  executionId: string
  patch: string
  path: string
  totalLines?: number
}

type GapExpandDirection = 'down' | 'up'

interface ParsedDiffHunk {
  body: string
  headerSuffix: string
  newCount: number
  newStart: number
  oldCount: number
  oldStart: number
  text: string
}

export function buildCommentExtendData(comments: DiffCommentDraft[]): {
  newFile: Record<string, CommentExtendData>
  oldFile: Record<string, CommentExtendData>
} {
  const oldFileMap = new Map<number, DiffCommentDraft[]>()
  const newFileMap = new Map<number, DiffCommentDraft[]>()
  for (const c of comments) {
    const target = (c.side ?? 'new') === 'old' ? oldFileMap : newFileMap
    const existing = target.get(c.line)
    if (existing) {
      existing.push(c)
    } else {
      target.set(c.line, [c])
    }
  }
  const toFileMap = (map: Map<number, DiffCommentDraft[]>) => {
    const file: Record<string, CommentExtendData> = {}
    for (const [line, list] of map.entries()) {
      file[String(line)] = { data: { comments: list } }
    }
    return file
  }
  return { newFile: toFileMap(newFileMap), oldFile: toFileMap(oldFileMap) }
}

export function DiffHunkViewer({
  chatId,
  comments,
  executionId,
  patch,
  path,
  totalLines,
}: DiffHunkViewerProps) {
  const isMobile = useIsMobile()
  const { resolvedTheme } = useTheme()
  const theme = resolvedTheme === 'dark' ? 'dark' : 'light'

  const diffViewMode = useDiffReviewStore((state) => state.diffViewMode)
  const activeCommentBox = useDiffReviewStore((state) => state.activeCommentBox)
  const setActiveCommentBox = useDiffReviewStore((state) => state.setActiveCommentBox)

  const [topContextLines, setTopContextLines] = useState<string[]>([])
  const [bottomContextLines, setBottomContextLines] = useState<string[]>([])
  const [gapDownContextLines, setGapDownContextLines] = useState<Record<number, string[]>>({})
  const [gapUpContextLines, setGapUpContextLines] = useState<Record<number, string[]>>({})
  const [isExpandingTop, setIsExpandingTop] = useState(false)
  const [isExpandingBottom, setIsExpandingBottom] = useState(false)
  const [expandingGap, setExpandingGap] = useState<null | {
    direction: GapExpandDirection
    index: number
  }>(null)
  const [hasReachedTop, setHasReachedTop] = useState(false)
  const [hasReachedBottom, setHasReachedBottom] = useState(false)
  const [hasReachedGapEnd, setHasReachedGapEnd] = useState<Record<number, boolean>>({})

  // Reset expanded slices when file path or base patch changes
  useEffect(() => {
    setTopContextLines([])
    setBottomContextLines([])
    setGapDownContextLines({})
    setGapUpContextLines({})
    setHasReachedTop(false)
    setHasReachedBottom(false)
    setHasReachedGapEnd({})
    setExpandingGap(null)
  }, [patch, path])

  const fileLang = useMemo(() => getFileLanguage(path), [path])

  // Context expansion: calculate bounds
  const initialFirstOldStart = useMemo(() => {
    const match = /@@\s+-(\d+)/.exec(patch)
    return match ? parseInt(match[1], 10) : 1
  }, [patch])

  const initialLastNewEnd = useMemo(() => {
    const matches = Array.from(patch.matchAll(/@@\s+-\d+(?:,\d+)?\s+\+(\d+)(?:,(\d+))?\s+@@/g))
    if (matches.length === 0) return 1
    const last = matches[matches.length - 1]
    const start = parseInt(last[1], 10)
    const count = last[2] ? parseInt(last[2], 10) : 1
    return start + count
  }, [patch])

  const currentFirstOldStart = Math.max(1, initialFirstOldStart - topContextLines.length)
  const currentLastNewEnd = initialLastNewEnd + bottomContextLines.length

  const isAtTop = hasReachedTop || currentFirstOldStart <= 1
  const isAtBottom =
    hasReachedBottom || (totalLines !== undefined && currentLastNewEnd >= totalLines)

  // Combine base patch with expanded context lines so git-diff-view renders them with native syntax highlighting
  const effectivePatch = useMemo(() => {
    return buildEffectivePatch(
      patch,
      topContextLines,
      bottomContextLines,
      gapDownContextLines,
      gapUpContextLines,
    )
  }, [patch, topContextLines, bottomContextLines, gapDownContextLines, gapUpContextLines])

  const parsedDiff = useMemo(() => parseDiffHunks(effectivePatch), [effectivePatch])
  const effectiveHunks = parsedDiff.hunks
  const hunkCount = effectiveHunks.length

  const hasRemainingGap = (gapIndex: number): boolean => {
    if (hasReachedGapEnd[gapIndex]) return false
    const upper = effectiveHunks.at(gapIndex)
    const lower = effectiveHunks.at(gapIndex + 1)
    if (!upper || !lower) return false
    return upper.newStart + upper.newCount - 1 < lower.newStart - 1
  }

  const handleExpandTop = async () => {
    if (isAtTop || isExpandingTop) return
    setIsExpandingTop(true)
    try {
      const fromLine = Math.max(1, currentFirstOldStart - 10)
      const toLine = currentFirstOldStart - 1
      const res = await fetchFileSlice(chatId, executionId, path, fromLine, toLine)
      if (res.lines.length === 0 || fromLine <= 1) {
        setHasReachedTop(true)
      }
      if (res.lines.length > 0) {
        setTopContextLines((prev) => [...res.lines, ...prev])
      }
    } catch {
      setHasReachedTop(true)
    } finally {
      setIsExpandingTop(false)
    }
  }

  const handleExpandBottom = async () => {
    if (isAtBottom || isExpandingBottom) return
    setIsExpandingBottom(true)
    try {
      const fromLine = currentLastNewEnd
      const toLine =
        totalLines !== undefined
          ? Math.min(totalLines, currentLastNewEnd + 10)
          : currentLastNewEnd + 10
      const res = await fetchFileSlice(chatId, executionId, path, fromLine, toLine)
      if (res.lines.length === 0) {
        setHasReachedBottom(true)
      } else {
        if (
          (totalLines !== undefined && fromLine + res.lines.length - 1 >= totalLines) ||
          res.lines.length < toLine - fromLine + 1
        ) {
          setHasReachedBottom(true)
        }
        setBottomContextLines((prev) => [...prev, ...res.lines])
      }
    } catch {
      setHasReachedBottom(true)
    } finally {
      setIsExpandingBottom(false)
    }
  }

  const handleExpandGap = async (gapIndex: number, direction: GapExpandDirection) => {
    if (expandingGap !== null || !hasRemainingGap(gapIndex)) return
    const upper = effectiveHunks.at(gapIndex)
    const lower = effectiveHunks.at(gapIndex + 1)
    if (!upper || !lower) return
    const gapStart = upper.newStart + upper.newCount
    const gapEnd = lower.newStart - 1
    if (gapStart > gapEnd) return
    const fromLine = direction === 'down' ? gapStart : Math.max(gapStart, gapEnd - 9)
    const toLine = direction === 'down' ? Math.min(gapEnd, gapStart + 9) : gapEnd
    setExpandingGap({ direction, index: gapIndex })
    try {
      const res = await fetchFileSlice(chatId, executionId, path, fromLine, toLine)
      if (res.lines.length === 0) {
        setHasReachedGapEnd((prev) => ({ ...prev, [gapIndex]: true }))
      } else if (direction === 'down') {
        setGapDownContextLines((prev) => ({
          ...prev,
          [gapIndex]: [...(prev[gapIndex] ?? []), ...res.lines],
        }))
      } else {
        setGapUpContextLines((prev) => ({
          ...prev,
          [gapIndex + 1]: [...res.lines, ...(prev[gapIndex + 1] ?? [])],
        }))
      }
    } catch {
      setHasReachedGapEnd((prev) => ({ ...prev, [gapIndex]: true }))
    } finally {
      setExpandingGap(null)
    }
  }

  // Synchronously initialize one DiffFile instance per hunk to avoid empty-render race conditions
  const diffFileInstances = useMemo(() => {
    return parsedDiff.hunks.map((hunk, i) => {
      try {
        const file = new DiffFile(
          path,
          '',
          path,
          '',
          normalizeDiffPatch(`${i === 0 ? parsedDiff.header : ''}${hunk.text}`, path),
          fileLang,
          fileLang,
        )
        file.initTheme(theme)
        file.initRaw()
        file.buildSplitDiffLines()
        file.buildUnifiedDiffLines()
        file.initSyntax()
        return file
      } catch {
        return null
      }
    })
  }, [parsedDiff, path, fileLang, theme])

  // Group existing comments by line number and side so each comment renders only on its own side
  const extendData = useMemo(() => buildCommentExtendData(comments), [comments])

  if (hunkCount === 0) {
    return (
      <div
        className="bg-card text-muted-foreground flex flex-col p-4 font-mono text-xs"
        data-testid="diff-hunk-viewer"
      >
        <div className="py-4 text-center">
          No diff content to display (empty file or mode change)
        </div>
      </div>
    )
  }

  return (
    <div
      className="bg-card text-foreground flex flex-col font-mono text-xs"
      data-testid="diff-hunk-viewer"
    >
      {/* Expand Top Context Button */}
      {!isAtTop && (
        <div className="border-border/40 bg-muted/20 flex items-center justify-center border-b py-0">
          <Button
            className="text-muted-foreground hover:text-foreground h-6 text-[11px]"
            disabled={isExpandingTop}
            onClick={() => void handleExpandTop()}
            size="sm"
            variant="link"
          >
            {isExpandingTop ? (
              <Loader2 className="mr-1 h-3 w-3 animate-spin" />
            ) : (
              <ChevronUp className="mr-1 h-3 w-3" />
            )}
            Show more
          </Button>
        </div>
      )}

      {/* One scroll container per hunk keeps hunk boundaries and expand controls fixed
          while wide diffs scroll l/r internally */}
      {diffFileInstances.map((diffFileInstance, hunkIndex) => (
        <Fragment key={hunkIndex}>
          {diffFileInstance && (
            <div
              className="overflow-x-auto overscroll-x-contain"
              data-testid={`diff-hunk-scroll-${hunkIndex}`}
            >
              <DiffView
                diffFile={diffFileInstance}
                diffViewAddWidget
                diffViewFontSize={isMobile ? 9 : 11}
                diffViewHighlight
                diffViewMode={
                  !isMobile && diffViewMode === 'split' ? DiffModeEnum.Split : DiffModeEnum.Unified
                }
                diffViewTheme={theme}
                diffViewWrap={false}
                extendData={extendData}
                onAddWidgetClick={(lineNumber: number, side: SplitSide) => {
                  setActiveCommentBox({
                    line: lineNumber,
                    path,
                    side: side === 1 ? 'old' : 'new',
                  })
                }}
                renderExtendLine={({ lineNumber, side }) => {
                  const sideName = side === 1 ? 'old' : 'new'
                  const lineComments = comments.filter(
                    (c) => c.line === lineNumber && (c.side ?? 'new') === sideName,
                  )
                  if (lineComments.length === 0) return null
                  return (
                    <DiffInlineComment
                      comments={lineComments}
                      executionId={executionId}
                      isDrafting={false}
                      line={lineNumber}
                      onCloseDraft={() => {}}
                      path={path}
                      side={sideName}
                    />
                  )
                }}
                renderWidgetLine={({ lineNumber, onClose, side: widgetSide }) => {
                  const sideName = widgetSide === 1 ? 'old' : 'new'
                  const isDrafting =
                    activeCommentBox?.path === path &&
                    activeCommentBox.line === lineNumber &&
                    (activeCommentBox.side ?? sideName) === sideName
                  const lineComments = comments.filter(
                    (c) => c.line === lineNumber && (c.side ?? 'new') === sideName,
                  )
                  if (!isDrafting && lineComments.length === 0) return null

                  return (
                    <DiffInlineComment
                      codeSnippet={activeCommentBox?.codeSnippet}
                      comments={lineComments}
                      executionId={executionId}
                      isDrafting={isDrafting}
                      line={lineNumber}
                      onCloseDraft={() => {
                        setActiveCommentBox(null)
                        onClose()
                      }}
                      path={path}
                      side={sideName}
                    />
                  )
                }}
              />
            </div>
          )}

          {/* Expand Context Between Hunks: up adds lines to the next hunk, down adds lines to the previous hunk */}
          {hunkIndex < diffFileInstances.length - 1 && hasRemainingGap(hunkIndex) && (
            <div className="border-border/40 bg-muted/20 flex items-center justify-center gap-1 border-y py-1">
              <Button
                className="text-muted-foreground hover:text-foreground h-6 px-1.5 text-[11px]"
                disabled={expandingGap?.index === hunkIndex}
                onClick={() => void handleExpandGap(hunkIndex, 'up')}
                size="sm"
                title="Show more lines above"
                variant="link"
              >
                {expandingGap?.index === hunkIndex && expandingGap.direction === 'up' ? (
                  <Loader2 className="h-3 w-3 animate-spin" />
                ) : (
                  <ChevronUp className="h-3 w-3" />
                )}
                Show
              </Button>
              <Button
                className="text-muted-foreground hover:text-foreground h-6 px-1.5 text-[11px]"
                disabled={expandingGap?.index === hunkIndex}
                onClick={() => void handleExpandGap(hunkIndex, 'down')}
                size="sm"
                title="Show more lines below"
                variant="link"
              >
                More
                {expandingGap?.index === hunkIndex && expandingGap.direction === 'down' ? (
                  <Loader2 className="h-3 w-3 animate-spin" />
                ) : (
                  <ChevronDown className="h-3 w-3" />
                )}
              </Button>
            </div>
          )}
        </Fragment>
      ))}

      {/* Expand Bottom Context Button */}
      {!isAtBottom && (
        <div className="border-border/40 bg-muted/20 flex items-center justify-center border-t py-1">
          <Button
            className="text-muted-foreground hover:text-foreground h-6 text-[11px]"
            disabled={isExpandingBottom}
            onClick={() => void handleExpandBottom()}
            size="sm"
            variant="link"
          >
            {isExpandingBottom ? (
              <Loader2 className="mr-1 h-3 w-3 animate-spin" />
            ) : (
              <ChevronDown className="mr-1 h-3 w-3" />
            )}
            Show more
          </Button>
        </div>
      )}
    </div>
  )
}

function buildEffectivePatch(
  rawPatch: string,
  topLines: string[],
  bottomLines: string[],
  gapDownLinesByHunk: Record<number, string[]>,
  gapUpLinesByHunk: Record<number, string[]>,
): string {
  if (
    topLines.length === 0 &&
    bottomLines.length === 0 &&
    Object.keys(gapDownLinesByHunk).length === 0 &&
    Object.keys(gapUpLinesByHunk).length === 0
  ) {
    return rawPatch
  }

  const { header, hunks } = parseDiffHunks(rawPatch)
  if (hunks.length === 0) return rawPatch

  const parts = hunks.map((hunk, i) => {
    let body = hunk.body
    let oldStart = hunk.oldStart
    let oldCount = hunk.oldCount
    let newStart = hunk.newStart
    let newCount = hunk.newCount

    if (i === 0 && topLines.length > 0) {
      oldStart = Math.max(1, oldStart - topLines.length)
      oldCount += topLines.length
      newStart = Math.max(1, newStart - topLines.length)
      newCount += topLines.length
      body = formatContextLines(topLines) + body
    }

    const gapUpLines = gapUpLinesByHunk[i] ?? []
    if (gapUpLines.length > 0) {
      oldStart -= gapUpLines.length
      oldCount += gapUpLines.length
      newStart -= gapUpLines.length
      newCount += gapUpLines.length
      body = formatContextLines(gapUpLines) + body
    }

    const gapDownLines = gapDownLinesByHunk[i] ?? []
    if (gapDownLines.length > 0) {
      oldCount += gapDownLines.length
      newCount += gapDownLines.length
      body = `${body.trimEnd()}\n${formatContextLines(gapDownLines)}`
    }

    if (i === hunks.length - 1 && bottomLines.length > 0) {
      oldCount += bottomLines.length
      newCount += bottomLines.length
      body = `${body.trimEnd()}\n${formatContextLines(bottomLines)}`
    }

    return `@@ -${oldStart},${oldCount} +${newStart},${newCount} @@${hunk.headerSuffix}\n${body}`
  })

  return header + parts.join('')
}

function formatContextLines(lines: string[]): string {
  return `${lines.map((line) => ` ${line}`).join('\n')}\n`
}

function getFileLanguage(filePath: string): string {
  const fileName = filePath.split('/').pop() ?? filePath
  const langs = langMap.languages(fileName)
  if (langs.length > 0 && langs[0]) {
    return langs[0].toLowerCase()
  }
  const ext = fileName.split('.').pop()?.toLowerCase() ?? ''
  if (ext) {
    const extLangs = langMap.languages(ext)
    if (extLangs.length > 0 && extLangs[0]) {
      return extLangs[0].toLowerCase()
    }
  }
  return ext || 'plaintext'
}

function normalizeDiffPatch(rawPatch: string, path: string): string[] {
  if (!rawPatch.trim()) return []

  // If the patch already contains unified diff headers (--- and +++), pass it directly as one diff item
  if (rawPatch.includes('--- ') && rawPatch.includes('+++ ')) {
    return [rawPatch]
  }

  // If raw hunks starting with @@ are provided without headers, prepend synthetic headers
  return [`--- a/${path}\n+++ b/${path}\n${rawPatch}`]
}

function parseDiffHunks(rawPatch: string): { header: string; hunks: ParsedDiffHunk[] } {
  const hunkHeaderRegex = /^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)?$/gm
  const matches = Array.from(rawPatch.matchAll(hunkHeaderRegex))
  if (matches.length === 0) {
    return { header: rawPatch, hunks: [] }
  }
  const header = rawPatch.slice(0, matches[0].index)
  const hunks = matches.map((match, i) => {
    const start = match.index
    const end = i + 1 < matches.length ? matches[i + 1].index : rawPatch.length
    const text = rawPatch.slice(start, end)
    const newlineIndex = text.indexOf('\n')
    return {
      body: newlineIndex === -1 ? '' : text.slice(newlineIndex + 1),
      headerSuffix: match[5] || '',
      newCount: match[4] ? parseInt(match[4], 10) : 1,
      newStart: parseInt(match[3], 10),
      oldCount: match[2] ? parseInt(match[2], 10) : 1,
      oldStart: parseInt(match[1], 10),
      text,
    }
  })
  return { header, hunks }
}
