'use client'

import { useNavigate, useParams } from '@tanstack/react-router'
import {
  AlertCircle,
  BookOpen,
  ChevronRight,
  Clock,
  FileText,
  GitBranch,
  Loader2,
  RefreshCw,
  Settings,
  Terminal,
  XCircle,
} from 'lucide-react'
import React, { useCallback, useEffect, useState } from 'react'

import type { TeamWikiBatchDto, TeamWikiStatusDto, WikiPageDto } from '@/lib/wiki-api'

import { MarkdownMessage } from '@/components/chat/markdown-message'
import { IngestionStatusBadge } from '@/components/repos/ingestion-status-badge'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { useRepositories } from '@/hooks/use-repositories'
import {
  useTeamWikiBatchLogs,
  useTeamWikiPage,
  useTeamWikiStatus,
  useTeamWikiTree,
  useTriggerTeamWikiRebuild,
} from '@/hooks/use-wiki'
import { formatRelativeTime } from '@/lib/format'

export function TeamWikiView() {
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
  const params = useParams({ strict: false }) as { pageSlug?: string }
  const { pageSlug } = params
  const navigate = useNavigate()

  const { data: repositories, isLoading: isReposLoading } = useRepositories()
  const ingestedRepos = (repositories ?? []).filter((r) => r.ingestionStatus === 'SUCCESS')

  const { data: status, isLoading: isStatusLoading } = useTeamWikiStatus()
  const hasActiveBatch = Boolean(status?.activeBatch)
  const { data: tree, isLoading: isTreeLoading } = useTeamWikiTree(hasActiveBatch)
  const triggerRebuild = useTriggerTeamWikiRebuild()

  const [logsOpen, setLogsOpen] = useState(false)
  const [selectedBatchId, setSelectedBatchId] = useState<null | string>(null)
  const [showDebugLogs, setShowDebugLogs] = useState(false)

  const effectiveBatchId =
    selectedBatchId ?? status?.latestBatch?.id ?? status?.activeBatch?.id ?? null
  const { data: batchLogs = [], isLoading: isLogsLoading } = useTeamWikiBatchLogs(
    effectiveBatchId,
    logsOpen,
  )
  const debugLogCount = batchLogs.filter((log) => log.level === 'DEBUG').length
  const visibleLogs = showDebugLogs ? batchLogs : batchLogs.filter((log) => log.level !== 'DEBUG')

  // Redirect single-repo teams directly to that repository's wiki
  useEffect(() => {
    if (!isReposLoading && ingestedRepos.length === 1) {
      void navigate({
        params: { _splat: ingestedRepos[0].name },
        replace: true,
        to: '/wiki/repo/$',
      })
    }
  }, [isReposLoading, ingestedRepos, navigate])

  let selectedPageId: null | string = null
  if (tree && pageSlug) {
    for (const [id, page] of tree.allPagesMap.entries()) {
      if (page.pageSlug === pageSlug) {
        selectedPageId = id
        break
      }
    }
  } else if (tree && !pageSlug && tree.topLevel.length > 0) {
    selectedPageId = tree.topLevel[0].id
  }

  const { data: selectedPage, isLoading: isPageLoading } = useTeamWikiPage(selectedPageId)

  useEffect(() => {
    if (tree && !pageSlug && tree.topLevel.length > 0) {
      void navigate({
        params: { pageSlug: tree.topLevel[0].pageSlug },
        replace: true,
        to: '/wiki/team/$pageSlug',
      })
    }
  }, [tree, pageSlug, navigate])

  const handleSelectPage = (page: WikiPageDto) => {
    void navigate({
      params: { pageSlug: page.pageSlug },
      to: '/wiki/team/$pageSlug',
    })
  }

  const handleMarkdownNavigate = useCallback(
    (path: string) => {
      void navigate({ to: path })
    },
    [navigate],
  )

  const breadcrumbs: WikiPageDto[] = []
  if (tree && selectedPage) {
    let current: undefined | WikiPageDto = selectedPage
    while (current) {
      breadcrumbs.unshift(current)
      current = current.parentPageId ? tree.allPagesMap.get(current.parentPageId) : undefined
    }
  }

  if (isReposLoading || isStatusLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
      </div>
    )
  }

  if (ingestedRepos.length === 0) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <div className="max-w-md text-center">
          <BookOpen className="text-muted-foreground mx-auto mb-3 h-10 w-10 opacity-50" />
          <h2 className="text-lg font-semibold">No ingested repositories yet</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            Ingest repositories in the Repos view to generate per-repository wikis and the team-wide
            architecture wiki.
          </p>
          <Button
            className="mt-4"
            onClick={() => {
              void navigate({ to: '/repos' })
            }}
          >
            <GitBranch className="mr-2 h-4 w-4" />
            Go to Repos
          </Button>
        </div>
      </div>
    )
  }

  if (ingestedRepos.length === 1) {
    return (
      <div className="flex h-full items-center justify-center">
        <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
      </div>
    )
  }

  const isBuildRunning =
    status?.latestBatch?.status === 'QUEUED' || status?.latestBatch?.status === 'PROCESSING'

  return (
    <div className="flex min-h-full flex-1 flex-col overflow-visible md:h-full md:overflow-hidden">
      <div className="border-border flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 sm:px-8">
        <div className="flex flex-wrap items-center gap-2.5">
          <BookOpen className="text-muted-foreground h-4 w-4" />
          <span className="text-sm font-semibold">Team Wiki</span>
          {formatStatusBadge(status)}
          <span className="text-muted-foreground text-xs">
            {status?.activeBatch?.repoCount ?? ingestedRepos.length} repositories
          </span>
          {(status?.nonStructuralChangedRepoCount ?? 0) > 0 && (
            <span className="text-muted-foreground text-xs">
              · {status!.nonStructuralChangedRepoCount} re-ingested since last build
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <Button
            aria-label="Team Wiki settings and logs"
            onClick={() => {
              setSelectedBatchId(status?.latestBatch?.id ?? status?.activeBatch?.id ?? null)
              setLogsOpen(true)
            }}
            size="icon"
            title="Team Wiki settings and logs"
            variant="ghost"
          >
            <Settings className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {!hasActiveBatch ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <div className="max-w-md text-center">
            {isBuildRunning ? (
              <>
                <Loader2 className="text-muted-foreground mx-auto mb-3 h-8 w-8 animate-spin" />
                <h2 className="text-lg font-semibold">Building Team Wiki</h2>
                <p className="text-muted-foreground mt-1 text-sm">
                  Synthesizing cross-repository architecture and clusters across{' '}
                  {ingestedRepos.length} repositories.
                </p>
                <Button
                  className="mt-4"
                  onClick={() => {
                    setSelectedBatchId(status.latestBatch ? status.latestBatch.id : null)
                    setLogsOpen(true)
                  }}
                  size="sm"
                  variant="outline"
                >
                  View Build Logs
                </Button>
              </>
            ) : status?.latestBatch?.status === 'FAILED' ? (
              <>
                <AlertCircle className="text-destructive mx-auto mb-3 h-8 w-8" />
                <h2 className="text-lg font-semibold">Team Wiki Build Failed</h2>
                <p className="text-muted-foreground mt-1 text-sm">
                  {status.latestBatch.errorMessage || 'An error occurred while building the wiki.'}
                </p>
                <div className="mt-4 flex justify-center gap-2">
                  <Button onClick={() => triggerRebuild.mutate('FULL')} size="sm">
                    Retry Build
                  </Button>
                  <Button
                    onClick={() => {
                      setSelectedBatchId(status.latestBatch?.id ?? null)
                      setLogsOpen(true)
                    }}
                    size="sm"
                    variant="outline"
                  >
                    Inspect Logs
                  </Button>
                </div>
              </>
            ) : (
              <>
                <BookOpen className="text-muted-foreground mx-auto mb-3 h-10 w-10 opacity-50" />
                <h2 className="text-lg font-semibold">
                  {status?.latestBatch ? 'Team Wiki Update Pending' : 'Team Wiki Not Built Yet'}
                </h2>
                <p className="text-muted-foreground mt-1 text-sm">
                  Automatic builds trigger once active ingestions settle, or you can start one now.
                </p>
                <Button className="mt-4" onClick={() => triggerRebuild.mutate('FULL')} size="sm">
                  {status?.latestBatch ? 'Update Wiki' : 'Build Team Wiki'}
                </Button>
              </>
            )}
          </div>
        </div>
      ) : isTreeLoading || isPageLoading ? (
        <div className="flex flex-1 items-center justify-center">
          <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
        </div>
      ) : selectedPage ? (
        <ScrollArea className="min-h-full flex-1 overflow-visible md:h-full md:overflow-hidden [&>[data-radix-scroll-area-viewport]]:!overflow-visible md:[&>[data-radix-scroll-area-viewport]]:!overflow-auto">
          <div className="mx-auto max-w-5xl p-4 sm:p-8" data-testid="wiki-content">
            {breadcrumbs.length > 0 && (
              <div className="text-muted-foreground mb-4 flex flex-wrap items-center gap-1 text-xs">
                <span className="font-medium">Team Wiki</span>
                {breadcrumbs.map((crumb, idx) => (
                  <React.Fragment key={crumb.id}>
                    <ChevronRight className="h-3 w-3 opacity-60" />
                    {idx === breadcrumbs.length - 1 ? (
                      <span className="text-foreground max-w-[180px] truncate font-medium">
                        {crumb.title}
                      </span>
                    ) : (
                      <button
                        className="hover:text-foreground max-w-[180px] truncate transition-colors hover:underline"
                        onClick={() => handleSelectPage(crumb)}
                      >
                        {crumb.title}
                      </button>
                    )}
                  </React.Fragment>
                ))}
              </div>
            )}

            <div className="mb-6">
              <h1 className="text-2xl font-semibold">{selectedPage.title}</h1>
              <p className="text-muted-foreground mt-1 text-xs">/{selectedPage.pageSlug}</p>
            </div>

            <Card>
              <CardContent className="p-6">
                <MarkdownMessage
                  content={selectedPage.content}
                  onNavigate={handleMarkdownNavigate}
                  variant="wiki"
                />
              </CardContent>
            </Card>
          </div>
        </ScrollArea>
      ) : (
        <div className="flex flex-1 items-center justify-center">
          <div className="text-muted-foreground text-center">
            <FileText className="mx-auto mb-3 h-10 w-10 opacity-40" />
            <p className="text-sm">Page not found</p>
          </div>
        </div>
      )}

      <Sheet onOpenChange={setLogsOpen} open={logsOpen}>
        <SheetContent
          className="flex w-full flex-col sm:max-w-2xl lg:max-w-4xl xl:max-w-5xl"
          side="right"
        >
          <SheetHeader>
            <SheetTitle>Team Wiki Build History & Logs</SheetTitle>
            <SheetDescription>
              Inspect recent automatic and manual Team Wiki builds and step-level execution logs.
            </SheetDescription>
            <div className="flex flex-wrap items-center gap-2 pt-2">
              <Button
                disabled={isBuildRunning || triggerRebuild.isPending}
                onClick={() => triggerRebuild.mutate(hasActiveBatch ? 'INCREMENTAL' : 'FULL')}
                size="sm"
                variant="outline"
              >
                <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                {hasActiveBatch ? 'Update Wiki' : 'Build Wiki'}
              </Button>
              {hasActiveBatch && (
                <Button
                  disabled={isBuildRunning || triggerRebuild.isPending}
                  onClick={() => triggerRebuild.mutate('FULL')}
                  size="sm"
                  variant="ghost"
                >
                  Rebuild from Scratch
                </Button>
              )}
            </div>
          </SheetHeader>

          <ScrollArea className="min-h-0 flex-1 px-4 pb-6">
            <div className="space-y-4">
              {/* Build History */}
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base">Build History</CardTitle>
                </CardHeader>
                <CardContent>
                  {(status?.recentBatches ?? []).length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-8 text-center text-slate-500">
                      <Terminal className="mb-2 h-8 w-8 text-slate-700" />
                      <p className="text-sm">No builds yet.</p>
                    </div>
                  ) : (
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="border-b p-2">
                            <th className="text-muted-foreground pb-2 pl-2 text-left font-medium">
                              Status
                            </th>
                            <th className="text-muted-foreground pb-2 text-left font-medium">
                              Mode
                            </th>
                            <th className="text-muted-foreground pb-2 text-left font-medium">
                              Trigger
                            </th>
                            <th className="text-muted-foreground pb-2 text-left font-medium">
                              Started
                            </th>
                            <th className="text-muted-foreground pb-2 text-left font-medium">
                              Error
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {(status?.recentBatches ?? []).map((batch: TeamWikiBatchDto) => {
                            const isSelected = batch.id === effectiveBatchId
                            return (
                              <tr
                                className={`cursor-pointer p-2 transition-colors ${
                                  isSelected
                                    ? 'bg-accent/40 border-accent relative z-10 border'
                                    : 'hover:bg-muted/50'
                                }`}
                                key={batch.id}
                                onClick={() => setSelectedBatchId(batch.id)}
                              >
                                <td className="py-2 pl-2">
                                  <IngestionStatusBadge status={batch.status} />
                                </td>
                                <td className="py-2 text-xs font-medium">{batch.buildMode}</td>
                                <td className="text-muted-foreground py-2 text-xs">
                                  {batch.triggerKind} · {batch.repoCount} repos
                                </td>
                                <td className="text-muted-foreground py-2 text-xs">
                                  {formatRelativeTime(batch.startedAt)}
                                </td>
                                <td className="max-w-44 truncate py-2 text-xs">
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

              {/* Terminal Logs */}
              <div className="relative overflow-hidden rounded-lg border border-slate-800 bg-[#0d1117] p-4 shadow-sm">
                <div className="mb-3 flex items-center justify-between gap-2 border-b border-slate-800 pb-2">
                  <div className="flex items-center gap-2">
                    <Terminal className="h-4 w-4 text-slate-400" />
                    <span className="font-mono text-xs font-medium text-slate-300">Build Logs</span>
                  </div>
                  <div className="flex items-center gap-2">
                    {debugLogCount > 0 && (
                      <button
                        className={`rounded border px-2 py-0.5 font-mono text-[11px] transition-colors ${
                          showDebugLogs
                            ? 'border-slate-600 bg-slate-800 text-slate-200'
                            : 'border-slate-800 bg-slate-900 text-slate-400 hover:text-slate-200'
                        }`}
                        onClick={() => setShowDebugLogs((prev) => !prev)}
                        type="button"
                      >
                        {showDebugLogs
                          ? `Hide debug (${debugLogCount})`
                          : `Show debug (${debugLogCount})`}
                      </button>
                    )}
                    <span className="font-mono text-xs text-slate-500 select-none">
                      kratis-team-wiki
                    </span>
                  </div>
                </div>

                <ScrollArea className="h-72 w-full font-mono text-xs text-slate-300">
                  {!effectiveBatchId ? (
                    <div className="flex h-64 flex-col items-center justify-center gap-2 text-slate-500 select-none">
                      <Terminal className="mb-1 h-8 w-8 text-slate-700" />
                      <span>No Team Wiki build logs recorded yet.</span>
                    </div>
                  ) : isLogsLoading && batchLogs.length === 0 ? (
                    <div className="flex h-64 flex-col items-center justify-center gap-2 text-slate-500">
                      <Loader2 className="h-6 w-6 animate-spin text-slate-600" />
                      <span>Fetching logs...</span>
                    </div>
                  ) : visibleLogs.length > 0 ? (
                    <div className="space-y-1.5 pr-3">
                      {visibleLogs.map((log) => {
                        const isError = log.level === 'ERROR'
                        const isWarn = log.level === 'WARN' || log.level === 'WARNING'
                        const isDebug = log.level === 'DEBUG'
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
                                    : isDebug
                                      ? 'bg-slate-800 text-slate-400'
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
                                    : isDebug
                                      ? 'text-slate-400'
                                      : 'text-slate-200'
                              }
                            >
                              {log.message}
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  ) : batchLogs.length > 0 ? (
                    <div className="flex h-64 flex-col items-center justify-center gap-2 text-slate-500 select-none">
                      <Terminal className="mb-1 h-8 w-8 text-slate-700" />
                      <span>Only DEBUG logs recorded for this build.</span>
                    </div>
                  ) : (
                    <div className="flex h-64 flex-col items-center justify-center gap-2 text-slate-500 select-none">
                      <Terminal className="mb-1 h-8 w-8 animate-pulse text-slate-700" />
                      <span>No logs recorded</span>
                    </div>
                  )}
                </ScrollArea>
              </div>
            </div>
          </ScrollArea>
        </SheetContent>
      </Sheet>
    </div>
  )
}

function formatStatusBadge(status: TeamWikiStatusDto | undefined) {
  if (!status) return null
  const latest = status.latestBatch
  if (latest && (latest.status === 'QUEUED' || latest.status === 'PROCESSING')) {
    return (
      <Badge className="gap-1.5" variant="secondary">
        <Loader2 className="h-3 w-3 animate-spin" />
        Updating...
      </Badge>
    )
  }
  if (latest?.status === 'FAILED' && !status.activeBatch) {
    return (
      <Badge className="gap-1.5" variant="destructive">
        <AlertCircle className="h-3 w-3" />
        Build failed
      </Badge>
    )
  }
  if (!status.activeBatch) {
    if (status.deferralReason !== 'NONE') {
      const label =
        status.deferralReason === 'IN_FLIGHT_REPOS'
          ? 'Deferred (repos ingesting)'
          : status.deferralReason === 'QUOTA_REACHED'
            ? 'Deferred (24h cap)'
            : 'Retry scheduled'
      return (
        <Badge className="gap-1.5" variant="outline">
          <Clock className="h-3 w-3" />
          {label}
        </Badge>
      )
    }
    return (
      <Badge variant="outline">{status.latestBatch ? 'Update pending' : 'Not built yet'}</Badge>
    )
  }
  if (status.dirty) {
    const label =
      status.deferralReason === 'IN_FLIGHT_REPOS'
        ? 'Deferred (repos ingesting)'
        : status.deferralReason === 'QUOTA_REACHED'
          ? 'Deferred (24h cap)'
          : status.deferralReason === 'FAILURE_BACKOFF'
            ? 'Retry scheduled'
            : 'Update pending'
    return (
      <Badge className="gap-1.5" variant="outline">
        <Clock className="h-3 w-3" />
        {label}
      </Badge>
    )
  }
  return (
    <Badge className="gap-1.5" variant="secondary">
      Up to date
    </Badge>
  )
}
