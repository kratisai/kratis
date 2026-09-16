'use client'

import { Link, useNavigate, useParams } from '@tanstack/react-router'
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  FileText,
  GitBranch,
  GitCommit,
  Loader2,
  MessageSquare,
  Play,
  Terminal,
  XCircle,
} from 'lucide-react'
import React, { useEffect, useRef } from 'react'

import type { ArchitecturePatternDto, DimensionStatDto } from '@/types/auth-types'

import { IngestionStatusBadge } from '@/components/repos/ingestion-status-badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  useBatchHistory,
  useBatchLogs,
  useBatchStats,
  useRepositories,
  useTriggerIngestion,
} from '@/hooks/use-repositories'
import { useStartChat } from '@/hooks/use-start-chat'
import { formatRelativeTime, formatSpend } from '@/lib/format'

export function RepositoryDrilldownView() {
  const { id } = useParams({ from: '/repos/$id' })
  const navigate = useNavigate()
  const triggerIngestionMutation = useTriggerIngestion()
  const { startChat } = useStartChat()

  const { data: repositories } = useRepositories()
  const repo = repositories?.find((r) => r.id === id)

  const { data: batchHistory, isLoading: isHistoryLoading } = useBatchHistory(id)
  const latestBatchId = repo?.latestBatchId ?? null

  const [selectedBatchId, setSelectedBatchId] = React.useState<null | string>(null)

  React.useEffect(() => {
    if (latestBatchId) {
      setSelectedBatchId(latestBatchId)
    }
  }, [latestBatchId])

  const { data: logs = [], isLoading: isLogsLoading } = useBatchLogs(id, selectedBatchId)
  const { data: batchStats, isLoading: isStatsLoading } = useBatchStats(id, selectedBatchId)

  const scrollAreaRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (scrollAreaRef.current) {
      const viewport = scrollAreaRef.current.querySelector<HTMLElement>(
        '[data-radix-scroll-area-viewport]',
      )
      if (viewport) {
        viewport.scrollTop = viewport.scrollHeight
      }
    }
  }, [logs])

  const activeStatus = repo?.ingestionStatus ?? 'INACTIVE'
  const isRunning = activeStatus === 'QUEUED' || activeStatus === 'PROCESSING'

  if (!repo) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <h2 className="text-xl font-semibold">Repository not found</h2>
          <p className="text-muted-foreground mt-2">
            The repository you're looking for doesn't exist or you don't have access to it.
          </p>
          <Button
            className="mt-4"
            onClick={() => {
              void navigate({ to: '/repos' })
            }}
          >
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back to Repositories
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-full overflow-visible p-6 md:h-full md:overflow-auto">
      <div className="mx-auto max-w-4xl space-y-6">
        {/* Header with back button */}
        <div className="flex items-center gap-4">
          <Button
            onClick={() => {
              void navigate({ to: '/repos' })
            }}
            size="icon"
            variant="ghost"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-2xl font-semibold">{repo.name}</h1>
            <p className="text-muted-foreground text-sm">{repo.url}</p>
          </div>
        </div>

        {/* Status and Metadata Card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Repository Details</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1">
                <p className="text-muted-foreground text-sm">Ingestion Status</p>
                <IngestionStatusBadge
                  queuePosition={repo.queuePosition}
                  status={repo.ingestionStatus}
                />
              </div>

              <div className="space-y-1">
                <p className="text-muted-foreground text-sm">Branch</p>
                <div className="flex items-center gap-1">
                  <GitBranch className="h-4 w-4" />
                  <span className="text-sm">{repo.branch}</span>
                </div>
              </div>

              {repo.commitHash && (
                <div className="space-y-1">
                  <p className="text-muted-foreground text-sm">Commit</p>
                  <div className="flex items-center gap-1">
                    <GitCommit className="h-4 w-4" />
                    <code className="bg-muted rounded px-1 py-0.5 font-mono text-xs">
                      {repo.commitHash.substring(0, 7)}
                    </code>
                  </div>
                </div>
              )}

              {repo.lastIngestedAt && (
                <div className="space-y-1">
                  <p className="text-muted-foreground text-sm">Last Ingested</p>
                  <p className="text-sm">{formatRelativeTime(repo.lastIngestedAt)}</p>
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        {/* Quick Actions Card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Quick Actions</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={
                  triggerIngestionMutation.isPending ||
                  repo.ingestionStatus === 'QUEUED' ||
                  repo.ingestionStatus === 'PROCESSING'
                }
                onClick={() => triggerIngestionMutation.mutate(repo.id)}
              >
                <Play className="mr-2 h-4 w-4" />
                Ingest Now
              </Button>
              <Button
                onClick={() => {
                  startChat(`Tell me about repository "${repo.name}".`)
                }}
                variant="outline"
              >
                <MessageSquare className="mr-2 h-4 w-4" />
                Ask Kratis
              </Button>
              <Button onClick={() => window.open(repo.url, '_blank')} variant="outline">
                View Source
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* Live Log Streaming */}
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-lg">
                {selectedBatchId === latestBatchId ? 'Live Logs' : 'Historical Logs'}
              </CardTitle>
              {selectedBatchId !== latestBatchId && (
                <Button
                  disabled={!latestBatchId}
                  onClick={() => setSelectedBatchId(latestBatchId)}
                  size="sm"
                  variant="outline"
                >
                  View Latest
                </Button>
              )}
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {/* Active Status bar */}
              <div className="border-border/80 bg-accent/30 flex items-center justify-between rounded-xl border p-4">
                <div className="flex items-center gap-3">
                  {isRunning ? (
                    <Loader2 className="text-primary h-5 w-5 animate-spin" />
                  ) : activeStatus === 'SUCCESS' ? (
                    <CheckCircle2 className="h-5 w-5 text-green-500" />
                  ) : activeStatus === 'FAILED' ? (
                    <AlertCircle className="text-destructive h-5 w-5" />
                  ) : (
                    <Play className="text-muted-foreground h-5 w-5" />
                  )}
                  <div>
                    <p className="text-sm font-semibold tracking-wide">
                      Ingestion Status:{' '}
                      <span className="text-primary font-bold">{activeStatus}</span>
                    </p>
                    {repo.commitHash && (
                      <p className="text-muted-foreground mt-0.5 font-mono text-xs">
                        Commit: {repo.commitHash}
                      </p>
                    )}
                  </div>
                </div>
                {isRunning && (
                  <span className="relative flex h-2 w-2">
                    <span className="bg-primary absolute inline-flex h-full w-full animate-ping rounded-full opacity-75"></span>
                    <span className="bg-primary relative inline-flex h-2 w-2 rounded-full"></span>
                  </span>
                )}
              </div>

              {/* Terminal Logs Viewer */}
              <div className="relative rounded-2xl border border-black/10 bg-slate-950 p-4 shadow-inner">
                <div className="mb-3 flex items-center justify-between border-b border-slate-800 pb-2">
                  <div className="flex items-center gap-1.5">
                    <div className="h-3.5 w-3.5 rounded-full bg-rose-500/80" />
                    <div className="h-3.5 w-3.5 rounded-full bg-amber-500/80" />
                    <div className="h-3.5 w-3.5 rounded-full bg-emerald-500/80" />
                  </div>
                  <span className="font-mono text-xs text-slate-500 select-none">
                    kratis-intel-session
                  </span>
                </div>

                <ScrollArea
                  className="h-80 w-full font-mono text-xs text-slate-300"
                  ref={scrollAreaRef}
                >
                  {isHistoryLoading ? (
                    <div className="flex h-70 flex-col items-center justify-center gap-2 text-slate-500">
                      <Loader2 className="h-6 w-6 animate-spin text-slate-600" />
                      <span>Loading ingestion session...</span>
                    </div>
                  ) : !selectedBatchId ? (
                    <div className="flex h-70 flex-col items-center justify-center gap-2 text-slate-500 select-none">
                      <Terminal className="mb-1 h-8 w-8 text-slate-700" />
                      <span>No active research batch logs found for this repository.</span>
                      <span className="text-[10px] text-slate-600">
                        Trigger Ingestion to see agentic logs
                      </span>
                    </div>
                  ) : isLogsLoading && logs.length === 0 ? (
                    <div className="flex h-70 flex-col items-center justify-center gap-2 text-slate-500">
                      <Loader2 className="h-6 w-6 animate-spin text-slate-600" />
                      <span>Fetching logs...</span>
                    </div>
                  ) : logs.length > 0 ? (
                    <div className="space-y-1.5 pr-3">
                      {logs.map((log) => {
                        const isError = log.level === 'ERROR'
                        const isWarn = log.level === 'WARN' || log.level === 'WARNING'
                        const dateStr = new Date(log.createdAt).toLocaleTimeString()
                        return (
                          <div
                            className="flex items-start gap-2 leading-relaxed break-all"
                            key={log.id}
                          >
                            <span className="shrink-0 text-slate-600 select-none">{dateStr}</span>
                            <span
                              className={`shrink-0 rounded px-1 py-0.5 text-[10px] font-semibold select-none ${
                                isError
                                  ? 'bg-red-950 text-red-400'
                                  : isWarn
                                    ? 'bg-amber-950 text-amber-400'
                                    : 'bg-blue-950 text-blue-400'
                              }`}
                            >
                              {log.level}
                            </span>
                            <span className="shrink-0 text-emerald-500 select-none">
                              [{log.step}]
                            </span>
                            <span
                              className={
                                isError
                                  ? 'text-rose-300'
                                  : isWarn
                                    ? 'text-amber-300'
                                    : 'text-slate-200'
                              }
                            >
                              {log.message}
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  ) : (
                    <div className="flex h-70 flex-col items-center justify-center gap-2 text-slate-500 select-none">
                      <Terminal className="mb-1 h-8 w-8 animate-pulse text-slate-700" />
                      <span>Initializing research logs terminal...</span>
                    </div>
                  )}
                </ScrollArea>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Ingestion History */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Ingestion History</CardTitle>
          </CardHeader>
          <CardContent>
            {isHistoryLoading ? (
              <div className="flex items-center justify-center py-8 text-slate-500">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                <span>Loading history...</span>
              </div>
            ) : !batchHistory || batchHistory.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-8 text-center text-slate-500">
                <Terminal className="mb-2 h-8 w-8 text-slate-700" />
                <p className="text-sm">No ingestion history yet.</p>
                <p className="text-xs text-slate-600">
                  Trigger an ingestion to see the history here.
                </p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b p-2">
                      <th className="text-muted-foreground pb-2 pl-2 text-left font-medium">
                        Status
                      </th>
                      <th className="text-muted-foreground pb-2 text-left font-medium">Commit</th>
                      <th className="text-muted-foreground pb-2 text-left font-medium">Started</th>
                      <th className="text-muted-foreground pb-2 text-left font-medium">
                        Completed
                      </th>
                      <th className="text-muted-foreground pb-2 text-left font-medium">Error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {batchHistory.map((batch) => {
                      const isSelected = selectedBatchId === batch.batchId
                      return (
                        <tr
                          className={`cursor-pointer p-2 transition-colors ${
                            isSelected ? 'border-accent relative z-10 border' : 'hover:bg-muted/50'
                          }`}
                          key={batch.batchId}
                          onClick={() => setSelectedBatchId(batch.batchId)}
                        >
                          <td className="py-2 pl-2">
                            <IngestionStatusBadge
                              status={
                                batch.status as 'FAILED' | 'PROCESSING' | 'QUEUED' | 'SUCCESS'
                              }
                            />
                          </td>
                          <td className="py-2">
                            {batch.commitHash ? (
                              <code className="bg-muted rounded px-1 py-0.5 font-mono text-xs">
                                {batch.commitHash.substring(0, 7)}
                              </code>
                            ) : (
                              <span className="text-muted-foreground">—</span>
                            )}
                          </td>
                          <td className="text-muted-foreground py-2">
                            {batch.startedAt ? formatRelativeTime(batch.startedAt) : '—'}
                          </td>
                          <td className="text-muted-foreground py-2">
                            {batch.completedAt ? formatRelativeTime(batch.completedAt) : '—'}
                          </td>
                          <td className="max-w-50 truncate py-2">
                            {batch.errorMessage ? (
                              <span
                                className="flex items-center gap-1 text-red-500"
                                title={batch.errorMessage}
                              >
                                <XCircle className="h-3 w-3 shrink-0" />
                                <span className="truncate">{batch.errorMessage}</span>
                              </span>
                            ) : (
                              <span className="text-muted-foreground">—</span>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Ingestion Statistics */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Ingestion Statistics</CardTitle>
          </CardHeader>
          <CardContent>
            {isStatsLoading ? (
              <div className="flex items-center justify-center py-8 text-slate-500">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                <span>Loading statistics...</span>
              </div>
            ) : !batchStats ? (
              <div className="flex flex-col items-center justify-center py-8 text-center text-slate-500">
                <Terminal className="mb-2 h-8 w-8 text-slate-700" />
                <p className="text-sm">No statistics available.</p>
                <p className="text-xs text-slate-600">
                  Trigger an ingestion to see statistics here.
                </p>
              </div>
            ) : (
              <div className="space-y-6">
                {/* Low-key key stats panel - four quadrants with a delicate centered cross */}
                <div className="grid gap-4 sm:grid-cols-3">
                  <div className="relative grid grid-cols-2 overflow-hidden rounded-lg border">
                    <div className="border-border/70 pointer-events-none absolute top-1/2 right-1/4 left-1/4 border-t" />
                    <div className="border-border/70 pointer-events-none absolute top-1/4 bottom-1/4 left-1/2 border-l" />
                    <div className="flex flex-col items-end justify-end p-6">
                      <p className="text-muted-foreground text-xs">Duration</p>
                      <p className="text-sm font-medium">
                        {batchStats.totalDurationSeconds != null
                          ? formatDuration(batchStats.totalDurationSeconds)
                          : '—'}
                      </p>
                    </div>
                    <div className="flex flex-col items-start justify-end p-6">
                      <p className="text-muted-foreground text-xs">Cost</p>
                      <p className="text-sm font-medium">{formatSpend(batchStats.totalSpend)}</p>
                    </div>
                    <div className="flex flex-col items-end justify-start p-6">
                      <p className="text-muted-foreground text-xs">Total Tokens</p>
                      <p className="text-sm font-medium">{formatTokens(batchStats.totalTokens)}</p>
                    </div>
                    <div className="flex flex-col items-start justify-start p-6">
                      <p className="text-muted-foreground text-xs">Tool Calls</p>
                      <p className="text-sm font-medium">
                        {formatTokens(batchStats.totalToolCalls)}
                      </p>
                    </div>
                  </div>
                  <div className="rounded-lg border p-4">
                    <p className="text-muted-foreground text-sm">Total Nodes</p>
                    <p className="mt-1 text-2xl font-semibold">{batchStats.totalNodes}</p>
                    {Object.keys(batchStats.nodeTypeCounts).length > 0 && (
                      <TypeTagCloud colorOffset={0} counts={batchStats.nodeTypeCounts} />
                    )}
                  </div>
                  <div className="rounded-lg border p-4">
                    <p className="text-muted-foreground text-sm">Total Edges</p>
                    <p className="mt-1 text-2xl font-semibold">{batchStats.totalEdges}</p>
                    {Object.keys(batchStats.edgeTypeCounts).length > 0 && (
                      <TypeTagCloud colorOffset={1} counts={batchStats.edgeTypeCounts} />
                    )}
                  </div>
                </div>

                {/* Dimension Categories Stats */}
                <div className="grid gap-4 sm:grid-cols-3">
                  {['domain', 'archetype', 'cross_cutting'].map((category, index) => {
                    const categoryStats = batchStats.dimensions.filter(
                      (d) => d.category.toLowerCase() === category,
                    )
                    return (
                      <div className="rounded-lg border p-4" key={category}>
                        <p className="text-muted-foreground text-sm capitalize">
                          {category.replace('_', ' ')}
                        </p>
                        <p className="mt-1 text-2xl font-semibold">{categoryStats.length}</p>
                        {categoryStats.length > 0 && (
                          <DimensionTagCloud colorOffset={2 + index} dimensions={categoryStats} />
                        )}
                      </div>
                    )
                  })}
                </div>

                {/* Architecture Patterns */}
                {batchStats.architecturePatterns.length > 0 && (
                  <div className="rounded-lg border p-4">
                    <p className="text-muted-foreground text-sm">Architecture Patterns</p>
                    <p className="mt-1 text-2xl font-semibold">
                      {batchStats.architecturePatterns.length}
                    </p>
                    <ArchitecturePatternList patterns={batchStats.architecturePatterns} />
                  </div>
                )}

                {/* Wiki Pages */}
                {batchStats.wikiPageSlugs.length > 0 && (
                  <div>
                    <h3 className="mb-2 text-sm font-medium">Generated Wiki Pages</h3>
                    <div className="rounded-lg border p-4">
                      <ul className="space-y-1 text-sm">
                        {batchStats.wikiPageSlugs.map((slug) => (
                          <li key={slug}>
                            <Link
                              className="text-muted-foreground hover:text-foreground flex items-center gap-1.5 hover:underline"
                              params={{ pageSlug: slug, repoId: repo.id }}
                              to="/wiki/$repoId/$pageSlug"
                            >
                              <FileText className="h-3.5 w-3.5 flex-shrink-0" />
                              {slug}
                            </Link>
                          </li>
                        ))}
                      </ul>
                    </div>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

// 0=violet(nodes), 1=sky(edges), 2=emerald(clusters), 3=amber(archetypes), 4=rose(architecture patterns)
const TAG_COLORS: Array<{ bg: string; border: string; text: string }> = [
  {
    bg: 'bg-violet-600 dark:bg-violet-800',
    border: 'border-violet-400 dark:border-violet-700',
    text: 'text-violet-700 dark:text-violet-300',
  },
  {
    bg: 'bg-sky-600 dark:bg-sky-800',
    border: 'border-sky-400 dark:border-sky-700',
    text: 'text-sky-700 dark:text-sky-300',
  },
  {
    bg: 'bg-emerald-600 dark:bg-emerald-800',
    border: 'border-emerald-400 dark:border-emerald-700',
    text: 'text-emerald-700 dark:text-emerald-300',
  },
  {
    bg: 'bg-amber-600 dark:bg-amber-800',
    border: 'border-amber-400 dark:border-amber-700',
    text: 'text-amber-700 dark:text-amber-300',
  },
  {
    bg: 'bg-rose-600 dark:bg-rose-800',
    border: 'border-rose-400 dark:border-rose-700',
    text: 'text-rose-700 dark:text-rose-300',
  },
]

const MAX_VISIBLE_TAGS = 8

function ArchitecturePatternList({ patterns }: { patterns: ArchitecturePatternDto[] }) {
  const MAX_VISIBLE = 3
  const [showAll, setShowAll] = React.useState(false)
  const visible = patterns.slice(0, MAX_VISIBLE)
  const displayed = showAll ? patterns : visible
  const hidden = patterns.length - visible.length

  return (
    <div className="mt-2 space-y-2">
      {displayed.map((pattern) => (
        <div className="bg-muted/30 rounded-md border p-3" key={pattern.name}>
          <p className="text-sm font-semibold text-rose-700 dark:text-rose-300">{pattern.name}</p>
          <p className="text-muted-foreground mt-1 text-xs leading-relaxed">
            {pattern.description}
          </p>
        </div>
      ))}
      {!showAll && hidden > 0 && (
        <button
          className="text-muted-foreground hover:text-foreground mt-1 text-xs underline-offset-2 hover:underline"
          onClick={() => setShowAll(true)}
        >
          +{hidden} more…
        </button>
      )}
      {showAll && patterns.length > MAX_VISIBLE && (
        <button
          className="text-muted-foreground hover:text-foreground mt-1 text-xs underline-offset-2 hover:underline"
          onClick={() => setShowAll(false)}
        >
          Show less
        </button>
      )}
    </div>
  )
}

function DimensionHoverCardBody({ dimension }: { dimension: DimensionStatDto }) {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-semibold">{dimension.name}</p>
        <span className="text-muted-foreground shrink-0 text-xs">
          {dimension.fileCount} file{dimension.fileCount === 1 ? '' : 's'}
        </span>
      </div>
      {dimension.synopsis && (
        <p className="text-muted-foreground text-xs leading-relaxed">{dimension.synopsis}</p>
      )}
      {dimension.topFiles.length > 0 && (
        <div className="space-y-1">
          <p className="text-muted-foreground text-xs font-medium">Top files</p>
          <ul className="space-y-0.5">
            {dimension.topFiles.map((file) => (
              <li className="truncate font-mono text-xs" key={file} title={file}>
                {file}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function DimensionTagCloud({
  colorOffset = 0,
  dimensions,
}: {
  colorOffset?: number
  dimensions: DimensionStatDto[]
}) {
  const sorted = [...dimensions].sort((a, b) => a.name.localeCompare(b.name))
  const visible = sorted.slice(0, MAX_VISIBLE_TAGS)
  const hidden = sorted.length - visible.length
  const [showAll, setShowAll] = React.useState(false)
  const displayed = showAll ? sorted : visible
  const color = TAG_COLORS[colorOffset % TAG_COLORS.length]

  return (
    <div className="mt-2 flex flex-wrap gap-1">
      {displayed.map((dimension) => (
        <HoverCard closeDelay={100} key={dimension.name} openDelay={150}>
          <HoverCardTrigger asChild>
            <span
              className={`inline-flex cursor-default items-center overflow-hidden rounded border ${color.border}`}
              style={{ fontSize: '10px', lineHeight: '1.4' }}
            >
              <span className={`${color.bg} px-1 py-px font-semibold text-white dark:text-white`}>
                {dimension.name}
              </span>
            </span>
          </HoverCardTrigger>
          <HoverCardContent>
            <DimensionHoverCardBody dimension={dimension} />
          </HoverCardContent>
        </HoverCard>
      ))}
      {!showAll && hidden > 0 && (
        <button
          className="text-muted-foreground hover:text-foreground inline-flex items-center py-px underline-offset-2 hover:underline"
          onClick={() => setShowAll(true)}
          style={{ fontSize: '8px', lineHeight: '1.4' }}
        >
          +{hidden} more…
        </button>
      )}
      {showAll && sorted.length > MAX_VISIBLE_TAGS && (
        <button
          className="text-muted-foreground hover:text-foreground inline-flex items-center py-px underline-offset-2 hover:underline"
          onClick={() => setShowAll(false)}
          style={{ fontSize: '8px', lineHeight: '1.4' }}
        >
          less
        </button>
      )}
    </div>
  )
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  if (minutes < 60) return `${minutes}m ${remainingSeconds}s`
  const hours = Math.floor(minutes / 60)
  const remainingMinutes = minutes % 60
  return `${hours}h ${remainingMinutes}m ${remainingSeconds}s`
}

function formatTokens(value: null | number): string {
  if (value == null) return '—'
  return new Intl.NumberFormat('en-US').format(value)
}

function TypeTagCloud({
  colorOffset = 0,
  counts,
}: {
  colorOffset?: number
  counts: Record<string, number>
}) {
  const entries = Object.entries(counts).sort((a, b) => b[1] - a[1])
  const visible = entries.slice(0, MAX_VISIBLE_TAGS)
  const hidden = entries.length - visible.length
  const [showAll, setShowAll] = React.useState(false)
  const displayed = showAll ? entries : visible

  return (
    <div className="mt-2 flex flex-wrap gap-1">
      {displayed.map(([type, count]) => {
        const color = TAG_COLORS[colorOffset % TAG_COLORS.length]
        return (
          <span
            className={`inline-flex items-center overflow-hidden rounded border ${color.border}`}
            key={type}
            style={{ fontSize: '10px', lineHeight: '1.4' }}
          >
            <span className={`${color.bg} px-1 py-px font-semibold text-white dark:text-white`}>
              {type}
            </span>
            <span className={`${color.text} px-1 py-px font-medium`}>{count}</span>
          </span>
        )
      })}
      {!showAll && hidden > 0 && (
        <button
          className="text-muted-foreground hover:text-foreground inline-flex items-center py-px underline-offset-2 hover:underline"
          onClick={() => setShowAll(true)}
          style={{ fontSize: '8px', lineHeight: '1.4' }}
        >
          +{hidden} more…
        </button>
      )}
      {showAll && entries.length > MAX_VISIBLE_TAGS && (
        <button
          className="text-muted-foreground hover:text-foreground inline-flex items-center py-px underline-offset-2 hover:underline"
          onClick={() => setShowAll(false)}
          style={{ fontSize: '8px', lineHeight: '1.4' }}
        >
          less
        </button>
      )}
    </div>
  )
}
