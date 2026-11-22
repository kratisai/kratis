import type { LucideIcon } from 'lucide-react'

import { Link } from '@tanstack/react-router'
import { formatDistanceToNow } from 'date-fns'
import {
  Activity,
  BarChart3,
  BookOpen,
  Calendar,
  CheckCircle2,
  Clock,
  Coins,
  Cpu,
  Filter,
  HelpCircle,
  MessageSquare,
  PieChart,
  RefreshCw,
  Sparkles,
  TrendingUp,
  User,
  XCircle,
} from 'lucide-react'
import { useState } from 'react'

import type { UsageLogEntry, UsageType } from '@/types/usage-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useUsageLogs, useUsageSummary } from '@/hooks/use-usage'
import { formatDurationSeconds } from '@/lib/format'
import { useAuthStore } from '@/store/auth-store'

const USAGE_TYPE_META: Partial<Record<UsageType, { icon: LucideIcon; label: string }>> = {
  CHAT: { icon: MessageSquare, label: 'Chat Session' },
  EXECUTION: { icon: Sparkles, label: 'Execution' },
  INGESTION: { icon: BarChart3, label: 'Repository Ingestion' },
  TEAM_WIKI: { icon: BookOpen, label: 'Team Wiki' },
}

const FILTERABLE_STATUSES = new Set(['COMPLETED', 'FAILED', 'IDLE', 'RUNNING', 'UNKNOWN'])

export function UsageView() {
  const currentTeamId = useAuthStore((state) => state.currentTeamId)
  const [timeframe, setTimeframe] = useState('7d')
  const [usageType, setUsageType] = useState('all')
  const [modelFilter, setModelFilter] = useState('all')
  const [agentFilter, setAgentFilter] = useState('all')
  const [userEmailFilter, setUserEmailFilter] = useState('all')
  const [statusFilter, setStatusFilter] = useState('all')
  const [shareMetric, setShareMetric] = useState<'agent-cost' | 'agent-time' | 'model'>('model')
  const [page, setPage] = useState(0)
  const pageSize = 15

  const { data: summary, refetch: refetchSummary } = useUsageSummary(currentTeamId, timeframe)
  const { data: logsPage, refetch: refetchLogs } = useUsageLogs(currentTeamId, {
    agent: agentFilter === 'all' ? undefined : agentFilter,
    model: modelFilter === 'all' ? undefined : modelFilter,
    page,
    size: pageSize,
    status: statusFilter === 'all' ? undefined : statusFilter,
    timeframe,
    usageType,
    userEmail: userEmailFilter === 'all' ? undefined : userEmailFilter,
  })

  const handleRefresh = () => {
    void refetchSummary()
    void refetchLogs()
  }

  const applyUsageTypeFilter = (nextType: UsageType, harnessName: null | string) => {
    const matches =
      usageType === nextType &&
      (nextType !== 'EXECUTION' || !harnessName || agentFilter === harnessName)
    if (matches) {
      setUsageType('all')
      setAgentFilter('all')
    } else {
      setUsageType(nextType)
      setAgentFilter(nextType === 'EXECUTION' && harnessName ? harnessName : 'all')
    }
    setPage(0)
  }

  const toggleModelFilter = (model: string) => {
    setModelFilter((current) => (current === model ? 'all' : model))
    setPage(0)
  }

  const toggleUserFilter = (email: string) => {
    setUserEmailFilter((current) => (current === email ? 'all' : email))
    setPage(0)
  }

  const toggleStatusFilter = (status: NonNullable<UsageLogEntry['status']>) => {
    const normalized = status === 'IDLE' ? 'COMPLETED' : status
    if (!FILTERABLE_STATUSES.has(normalized)) return
    setStatusFilter((current) => (current === normalized ? 'all' : normalized))
    setPage(0)
  }

  const currentPieData =
    shareMetric === 'model'
      ? summary?.modelShare || []
      : shareMetric === 'agent-time'
        ? summary?.agentShareByTime || []
        : summary?.agentShareByCost || []

  return (
    <div className="flex min-h-full flex-col space-y-6 overflow-visible p-6 md:h-full md:overflow-auto">
      {/* Header */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Usage & Analytics</h1>
          <p className="text-muted-foreground">
            Monitor API spend, token utilization, and activity logs across your team.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Select
            onValueChange={(value) => {
              setTimeframe(value)
              setPage(0)
            }}
            value={timeframe}
          >
            <SelectTrigger className="w-[160px]">
              <Calendar className="mr-2 h-4 w-4" />
              <SelectValue placeholder="Timeframe" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="24h">Last 24 Hours</SelectItem>
              <SelectItem value="7d">Last 7 Days</SelectItem>
              <SelectItem value="30d">Last 30 Days</SelectItem>
            </SelectContent>
          </Select>
          <Button onClick={handleRefresh} size="icon" title="Refresh Data" variant="outline">
            <RefreshCw className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid gap-4 sm:grid-cols-3">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-muted-foreground text-sm font-medium">Total Cost</CardTitle>
            <Coins className="text-primary h-4 w-4" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              ${summary ? summary.totalCost.toFixed(4) : '0.0000'}
            </div>
            <p className="text-muted-foreground mt-1 text-xs">Across all LLM calls & ingestions</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-muted-foreground text-sm font-medium">
              Total Tokens Used
            </CardTitle>
            <Cpu className="text-primary h-4 w-4" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {summary ? summary.totalTokens.toLocaleString() : '0'}
            </div>
            <p className="text-muted-foreground mt-1 text-xs">Prompt & completion tokens</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-muted-foreground text-sm font-medium">
              Total Operations
            </CardTitle>
            <Activity className="text-primary h-4 w-4" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {summary ? summary.totalOperations.toLocaleString() : '0'}
            </div>
            <p className="text-muted-foreground mt-1 text-xs">Executions, chats & batches</p>
          </CardContent>
        </Card>
      </div>

      {/* Charts Section */}
      <div className="grid gap-6 lg:grid-cols-3">
        {/* Trend Chart */}
        <Card className="flex flex-col lg:col-span-2">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <div>
              <CardTitle className="text-base font-semibold">Utilization Trend</CardTitle>
              <CardDescription>Time-series cost & token utilization</CardDescription>
            </div>
            <TrendingUp className="text-muted-foreground h-5 w-5" />
          </CardHeader>
          <CardContent className="flex min-h-[240px] flex-1 flex-col justify-center">
            {summary && summary.timeSeries.length > 0 ? (
              <div className="space-y-3">
                <div className="border-muted flex h-40 w-full items-end gap-1 border-b pt-6 pb-2">
                  {summary.timeSeries.map((point, idx) => {
                    const maxCost = Math.max(...summary.timeSeries.map((p) => p.cost), 0.001)
                    const heightPercent = Math.max((point.cost / maxCost) * 100, 8)
                    return (
                      <div
                        className="bg-primary/20 hover:bg-primary/40 group relative flex-1 rounded-t transition-all"
                        key={idx}
                        style={{ height: `${heightPercent}%` }}
                      >
                        <div className="bg-popover text-popover-foreground absolute bottom-full left-1/2 z-10 mb-1 hidden -translate-x-1/2 rounded p-1 text-xs whitespace-nowrap shadow-md group-hover:block">
                          ${point.cost.toFixed(3)} ({point.tokens.toLocaleString()} tk)
                        </div>
                      </div>
                    )
                  })}
                </div>
                <div className="text-muted-foreground flex justify-between px-1 text-xs">
                  <span>{new Date(summary.timeSeries[0].timestamp).toLocaleDateString()}</span>
                  <span>
                    {new Date(
                      summary.timeSeries[summary.timeSeries.length - 1].timestamp,
                    ).toLocaleDateString()}
                  </span>
                </div>
              </div>
            ) : (
              <div className="text-muted-foreground flex flex-col items-center justify-center py-12">
                <BarChart3 className="mb-2 h-10 w-10 opacity-40" />
                <p className="text-sm">No usage data recorded for this timeframe yet.</p>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Share Hollow Pie Chart */}
        <Card className="flex flex-col">
          <CardHeader className="pb-3">
            <div className="flex items-center justify-between">
              <CardTitle className="text-base font-semibold">Share Breakdown</CardTitle>
              <PieChart className="text-muted-foreground h-5 w-5" />
            </div>
            <div className="mt-2">
              <Tabs
                className="w-full"
                onValueChange={(val) =>
                  setShareMetric(val as 'agent-cost' | 'agent-time' | 'model')
                }
                value={shareMetric}
              >
                <TabsList className="grid h-8 w-full grid-cols-3 text-xs">
                  <TabsTrigger value="model">Model</TabsTrigger>
                  <TabsTrigger value="agent-time">Time</TabsTrigger>
                  <TabsTrigger value="agent-cost">Cost</TabsTrigger>
                </TabsList>
              </Tabs>
            </div>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col justify-center">
            {currentPieData.length > 0 ? (
              <div className="space-y-3">
                <div className="space-y-2">
                  {currentPieData.map((item, idx) => (
                    <div
                      className="group hover:bg-muted/50 flex cursor-pointer items-center justify-between rounded p-1.5 text-xs transition-colors"
                      key={idx}
                      onClick={() => {
                        if (shareMetric === 'model') {
                          setModelFilter(item.label)
                        } else {
                          setAgentFilter(item.label)
                        }
                        setPage(0)
                      }}
                    >
                      <div className="flex items-center gap-2 truncate">
                        <span className="bg-primary h-2.5 w-2.5 shrink-0 rounded-full opacity-80 group-hover:opacity-100" />
                        <span className="truncate font-medium">{item.label}</span>
                      </div>
                      <div className="text-muted-foreground flex shrink-0 items-center gap-3">
                        <span>
                          {shareMetric === 'agent-time'
                            ? formatDurationSeconds(item.cost)
                            : `$${item.cost.toFixed(3)}`}
                        </span>
                        <span className="text-foreground font-semibold">
                          {item.percentage.toFixed(1)}%
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
                <p className="text-muted-foreground border-t pt-2 text-center text-[11px]">
                  Click any slice to filter the log table below.
                </p>
              </div>
            ) : (
              <div className="text-muted-foreground flex flex-col items-center justify-center py-10">
                <PieChart className="mb-2 h-8 w-8 opacity-40" />
                <p className="text-xs">No breakdown available.</p>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Filter Controls Bar */}
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <div className="text-muted-foreground flex items-center gap-2 text-sm font-medium">
            <Filter className="h-4 w-4" />
            <span>Filters:</span>
          </div>

          <Select
            onValueChange={(value) => {
              setUsageType(value)
              if (value !== 'EXECUTION') setAgentFilter('all')
              setPage(0)
            }}
            value={usageType}
          >
            <SelectTrigger className="h-9 w-[140px] text-xs">
              <SelectValue placeholder="Usage Type" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Types</SelectItem>
              <SelectItem value="EXECUTION">Executions</SelectItem>
              <SelectItem value="INGESTION">Repository Ingestion</SelectItem>
              <SelectItem value="TEAM_WIKI">Team Wiki</SelectItem>
              <SelectItem value="CHAT">Chat Sessions</SelectItem>
            </SelectContent>
          </Select>

          <Select
            onValueChange={(value) => {
              setStatusFilter(value)
              setPage(0)
            }}
            value={statusFilter}
          >
            <SelectTrigger className="h-9 w-[140px] text-xs">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Statuses</SelectItem>
              <SelectItem value="RUNNING">Running</SelectItem>
              <SelectItem value="COMPLETED">Completed</SelectItem>
              <SelectItem value="FAILED">Failed</SelectItem>
              <SelectItem value="UNKNOWN">Unknown</SelectItem>
            </SelectContent>
          </Select>

          <Select
            onValueChange={(value) => {
              setModelFilter(value)
              setPage(0)
            }}
            value={modelFilter}
          >
            <SelectTrigger className="h-9 w-[140px] text-xs">
              <SelectValue placeholder="Model" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Models</SelectItem>
              {summary?.modelShare.map((m) => (
                <SelectItem key={m.label} value={m.label}>
                  {m.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            onValueChange={(value) => {
              setAgentFilter(value)
              setPage(0)
            }}
            value={agentFilter}
          >
            <SelectTrigger className="h-9 w-[150px] text-xs">
              <SelectValue placeholder="Agent / Harness" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Agents</SelectItem>
              {summary?.agentShareByCost.map((a) => (
                <SelectItem key={a.label} value={a.label}>
                  {a.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {userEmailFilter !== 'all' && (
            <Button
              aria-label="Clear user filter"
              className="h-9 text-xs"
              onClick={() => {
                setUserEmailFilter('all')
                setPage(0)
              }}
              size="sm"
              variant="outline"
            >
              <User className="h-3.5 w-3.5" />
              {userEmailFilter}
              <XCircle className="h-3.5 w-3.5" />
            </Button>
          )}

          {(usageType !== 'all' ||
            statusFilter !== 'all' ||
            modelFilter !== 'all' ||
            agentFilter !== 'all' ||
            userEmailFilter !== 'all') && (
            <Button
              className="text-muted-foreground hover:text-foreground h-9 text-xs"
              onClick={() => {
                setUsageType('all')
                setStatusFilter('all')
                setModelFilter('all')
                setAgentFilter('all')
                setUserEmailFilter('all')
                setPage(0)
              }}
              size="sm"
              variant="ghost"
            >
              Reset Filters
            </Button>
          )}
        </CardContent>
      </Card>

      {/* Usage Log Table */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base font-semibold">Activity & Usage Log</CardTitle>
          <CardDescription>
            Human-friendly activity feed across all agent operations
          </CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          <div className="relative w-full overflow-auto">
            <table className="w-full caption-bottom text-sm">
              <thead className="bg-muted/50 text-muted-foreground border-b text-xs font-medium">
                <tr className="[&_th]:px-4 [&_th]:py-3 [&_th]:text-left">
                  <th>Timestamp / Duration</th>
                  <th>Activity & Status</th>
                  <th>User</th>
                  <th>Model</th>
                  <th>Tokens & Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {logsPage && logsPage.content.length > 0 ? (
                  logsPage.content.map((entry) => {
                    const totalTokens = entry.models.reduce(
                      (total, model) => total + (model.totalTokens ?? 0),
                      0,
                    )
                    const totalSpend = entry.models.reduce(
                      (total, model) => total + (model.totalSpend ?? 0),
                      0,
                    )
                    const status = entry.status
                    return (
                      <tr
                        className="hover:bg-muted/30 transition-colors [&_td]:px-4 [&_td]:py-3"
                        key={entry.id}
                      >
                        {/* 1. Timestamp / Duration */}
                        <td>
                          <div className="text-foreground font-medium">
                            {entry.timestamp
                              ? formatDistanceToNow(new Date(entry.timestamp), { addSuffix: true })
                              : 'Just now'}
                          </div>
                          <div className="text-muted-foreground mt-0.5 flex items-center gap-1 text-xs">
                            <Clock className="h-3 w-3" />
                            <span>{formatDurationSeconds(entry.durationSeconds)}</span>
                          </div>
                        </td>

                        {/* 2. Activity & Status */}
                        <td>
                          {entry.targetUrl ? (
                            <Link
                              className="text-foreground hover:text-primary line-clamp-1 font-medium hover:underline"
                              to={entry.targetUrl as never}
                            >
                              {entry.activityTitle}
                            </Link>
                          ) : (
                            <div className="text-foreground line-clamp-1 font-medium">
                              {entry.activityTitle}
                            </div>
                          )}
                          <div className="mt-1 flex flex-wrap items-center gap-2">
                            <UsageTypeBadge
                              agentName={entry.agentName}
                              onClick={() => applyUsageTypeFilter(entry.usageType, entry.agentName)}
                              usageType={entry.usageType}
                            />
                            {status && entry.usageType !== 'CHAT' && (
                              <UsageStatusBadge
                                onClick={() => toggleStatusFilter(status)}
                                status={status}
                              />
                            )}
                          </div>
                        </td>

                        {/* 3. User */}
                        <td>
                          <button
                            aria-label={`Filter by user ${entry.userEmail}`}
                            className="text-foreground hover:text-primary flex cursor-pointer items-center gap-1.5 text-sm font-medium hover:underline"
                            onClick={() => toggleUserFilter(entry.userEmail)}
                            type="button"
                          >
                            <User className="text-muted-foreground h-3.5 w-3.5 shrink-0" />
                            <span className="max-w-[180px] truncate">{entry.userEmail}</span>
                          </button>
                        </td>

                        {/* 4. Model */}
                        <td>
                          <div className="flex flex-wrap gap-1">
                            {entry.models.map((model) => (
                              <button
                                aria-label={`Filter by model ${model.modelIdentifier}`}
                                className="text-muted-foreground bg-muted/50 hover:text-primary w-fit cursor-pointer rounded px-2 py-0.5 font-mono text-xs hover:underline"
                                key={`${model.modelKind ?? 'UNKNOWN'}-${model.modelIdentifier}`}
                                onClick={() => toggleModelFilter(model.modelIdentifier)}
                                type="button"
                              >
                                {model.modelIdentifier}
                              </button>
                            ))}
                          </div>
                        </td>

                        {/* 5. Tokens & Cost */}
                        <td>
                          <div className="text-muted-foreground flex flex-wrap items-center gap-x-1.5 text-xs">
                            <span className="text-foreground font-medium">
                              {totalTokens.toLocaleString()} tk
                            </span>
                            <span>•</span>
                            <span className="font-semibold text-emerald-600 dark:text-emerald-400">
                              ${totalSpend.toFixed(4)}
                            </span>
                          </div>
                        </td>
                      </tr>
                    )
                  })
                ) : (
                  <tr>
                    <td className="text-muted-foreground py-12 text-center" colSpan={5}>
                      No usage log entries found matching the current filters.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {logsPage && logsPage.totalPages > 1 && (
            <div className="flex items-center justify-between border-t p-4">
              <div className="text-muted-foreground text-xs">
                Showing page {page + 1} of {logsPage.totalPages} ({logsPage.totalElements} total
                entries)
              </div>
              <div className="flex gap-2">
                <Button
                  disabled={page === 0}
                  onClick={() => setPage((p) => Math.max(p - 1, 0))}
                  size="sm"
                  variant="outline"
                >
                  Previous
                </Button>
                <Button
                  disabled={page >= logsPage.totalPages - 1}
                  onClick={() => setPage((p) => p + 1)}
                  size="sm"
                  variant="outline"
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

const FALLBACK_USAGE_TYPE: { icon: LucideIcon } = {
  icon: HelpCircle,
}

function UsageTypeBadge({
  agentName,
  onClick,
  usageType,
}: {
  agentName: null | string
  onClick: () => void
  usageType: UsageType
}) {
  const meta = USAGE_TYPE_META[usageType] ?? { ...FALLBACK_USAGE_TYPE, label: usageType }
  const Icon = meta.icon
  const combinedLabel = usageType === 'EXECUTION' && agentName
  const label = combinedLabel ? `${agentName} execution` : meta.label
  const title = combinedLabel
    ? `Filter by ${agentName} executions`
    : usageType === 'EXECUTION'
      ? 'Filter by executions'
      : `Filter by ${meta.label}`
  return (
    <button
      className="bg-secondary text-secondary-foreground hover:bg-secondary/80 inline-flex w-fit cursor-pointer items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium transition-colors"
      onClick={onClick}
      title={title}
      type="button"
    >
      <Icon className="h-3 w-3" />
      {label}
    </button>
  )
}

const USAGE_STATUS_META: Partial<
  Record<
    NonNullable<UsageLogEntry['status']>,
    { className: string; icon: LucideIcon; label: string; spin?: boolean }
  >
> = {
  COMPLETED: {
    className: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    icon: CheckCircle2,
    label: 'Complete',
  },
  FAILED: {
    className: 'bg-destructive/10 text-destructive',
    icon: XCircle,
    label: 'Failed',
  },
  IDLE: {
    className: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    icon: CheckCircle2,
    label: 'Complete',
  },
  RUNNING: {
    className: 'bg-amber-500/10 text-amber-600 dark:text-amber-400',
    icon: RefreshCw,
    label: 'Active',
    spin: true,
  },
  UNKNOWN: {
    className: 'bg-muted text-muted-foreground',
    icon: HelpCircle,
    label: 'Unknown',
  },
}

const FALLBACK_USAGE_STATUS = {
  className: 'bg-muted text-muted-foreground',
  icon: HelpCircle,
  label: 'Unknown',
}

function UsageStatusBadge({
  onClick,
  status,
}: {
  onClick: () => void
  status: NonNullable<UsageLogEntry['status']>
}) {
  const meta = USAGE_STATUS_META[status] ?? { ...FALLBACK_USAGE_STATUS, label: status }
  const Icon = meta.icon
  return (
    <button
      className={`inline-flex cursor-pointer items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold transition-opacity hover:opacity-80 ${meta.className}`}
      onClick={onClick}
      title={`Filter by ${meta.label.toLowerCase()} status`}
      type="button"
    >
      <Icon className={meta.spin ? 'h-3 w-3 animate-spin' : 'h-3 w-3'} />
      {meta.label}
    </button>
  )
}
