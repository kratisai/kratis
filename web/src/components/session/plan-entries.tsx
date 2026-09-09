import { CheckCircle2, Circle, Loader2 } from 'lucide-react'

import type { PlanEntry, PlanEntryStatus } from '@/types/websocket-types'

import { cn } from '@/lib/utils'

interface PlanStats {
  completed: number
  inProgress: null | PlanEntry
  pending: number
  total: number
}

export function PlanEntriesList({ entries }: { entries: PlanEntry[] }) {
  return (
    <div className="py-1" data-testid="plan-entries">
      {entries.map((entry, index) => (
        <div className="flex items-start gap-2 px-3 py-1.5" key={index}>
          <div className="mt-0.5 shrink-0">{planEntryIcon(entry.status)}</div>
          <span
            className={cn(
              'min-w-0 flex-1 text-xs break-words',
              entry.status === 'completed' && 'text-muted-foreground line-through opacity-80',
            )}
            title={entry.content}
          >
            {entry.content}
          </span>
        </div>
      ))}
    </div>
  )
}

export function planEntryIcon(status: PlanEntryStatus) {
  if (status === 'in_progress') {
    return <Loader2 className="h-3 w-3 shrink-0 animate-spin text-blue-500" />
  }
  if (status === 'completed') {
    return <CheckCircle2 className="h-3 w-3 shrink-0 text-green-500" />
  }
  return <Circle className="text-muted-foreground h-3 w-3 shrink-0" />
}

export function planStats(entries: PlanEntry[]): PlanStats {
  let completed = 0
  let pending = 0
  let inProgress: null | PlanEntry = null
  for (const entry of entries) {
    if (entry.status === 'completed') {
      completed++
    } else {
      pending++
      if (entry.status === 'in_progress' && inProgress === null) {
        inProgress = entry
      }
    }
  }
  return { completed, inProgress, pending, total: entries.length }
}

export function planSummaryLabel(entries: PlanEntry[]): string {
  const stats = planStats(entries)
  if (stats.pending === 0) {
    return 'All Done'
  }
  if (stats.completed === 0) {
    return `${stats.pending} Tasks`
  }
  return `${stats.completed}/${stats.total}`
}
