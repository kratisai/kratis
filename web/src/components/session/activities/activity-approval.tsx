import {
  Ban,
  Check,
  CheckCircle2,
  ChevronDown,
  Clock,
  ShieldCheck,
  ShieldX,
  X,
  XCircle,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import type {
  CommandExecutionActivity,
  HitlResponse,
  PermissionOption,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'
import type { CreateHitlRuleRequest } from '@/types/hitl-rule-types'

import { HitlFeedbackField } from '@/components/session/activities/hitl-feedback-field'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { activityCommand, useActivityStore } from '@/store/activity-store'
import { SYSTEM_TIMEOUT_RESOLVED_BY } from '@/types/websocket-types'

interface ActivityApprovalProps {
  activity: ApprovalActivity
  executionId: string
}

type ApprovalActivity = CommandExecutionActivity | ToolExecutionActivity
type SegmentMark = 'allow' | 'deny'

export function ActivityApproval({ activity, executionId }: ActivityApprovalProps) {
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [rememberOpen, setRememberOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const cardRef = useRef<HTMLDivElement>(null)

  // Expanding the remember panel grows the card; keep its action buttons in view.
  useEffect(() => {
    if (rememberOpen) {
      cardRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    }
  }, [rememberOpen])
  const [roots, setRoots] = useState<Record<number, string | undefined>>({})
  const resolveHitl = useActivityStore((s) => s.resolveHitl)
  const command = activityCommand(activity)
  const hitlDetail = activity.detail?.hitl
  const options = activity.permissionOptions ?? hitlDetail?.options ?? []
  const segments = activity.permissionSegments ?? hitlDetail?.commandSegments ?? []
  const [marks, setMarks] = useState<Record<number, SegmentMark>>(() => {
    const initial: Record<number, SegmentMark> = {}
    segments.forEach((seg, idx) => {
      if (seg.preApproved) {
        initial[idx] = 'allow'
      }
    })
    return initial
  })
  const diff = activity.permissionDiff ?? activity.detail?.diff
  const hitlId = activity.actionId ?? command

  const allowOption =
    options.find((o) => o.kind === 'allow_once') ?? options.find((o) => o.kind.startsWith('allow'))
  const rejectOption =
    options.find((o) => o.kind === 'reject_once') ??
    options.find((o) => o.kind.startsWith('reject'))

  const markedEntries = Object.entries(marks)
  const hasAllowMark = markedEntries.some(([, mark]) => mark === 'allow')
  const hasDenyMark = markedEntries.some(([, mark]) => mark === 'deny')
  const rememberedRules: CreateHitlRuleRequest[] = markedEntries.flatMap(([index, mark]) => {
    const i = Number(index)
    const commandRoot = (roots[i] ?? segments[i].suggestedRoot).trim()
    if (commandRoot === '') return []
    return [
      {
        action: mark === 'allow' ? ('ALLOW' as const) : ('DENY' as const),
        commandRoot,
        ruleType: segments[i].ruleType,
      },
    ]
  })

  const isResolved =
    activity.state === 'active' || activity.state === 'error' || activity.state === 'completed'

  const submit = async (
    response: 'approved' | 'cancelled' | 'declined',
    optionId?: string,
    rules?: CreateHitlRuleRequest[],
  ) => {
    setIsSubmitting(true)
    try {
      await resolveHitl(executionId, hitlId, response, optionId, undefined, rules, feedback)
    } catch {
      // handled via websocket resolution
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleAllow = () =>
    submit(
      'approved',
      allowOption?.optionId,
      hasAllowMark && !hasDenyMark && rememberedRules.length > 0 ? rememberedRules : undefined,
    )

  const handleReject = () =>
    submit(
      'declined',
      rejectOption?.optionId,
      rememberedRules.length > 0 ? rememberedRules : undefined,
    )

  const handleCancel = () => submit('cancelled')

  const toggleMark = (index: number, mark: SegmentMark) => {
    setMarks((prev) => {
      const next = { ...prev }
      if (next[index] === mark) {
        delete next[index]
      } else {
        next[index] = mark
      }
      return next
    })
  }

  if (isResolved) {
    const resolution: HitlResponse =
      activity.hitlResponse ?? (activity.approved === true ? 'approved' : 'declined')
    const timedOut =
      resolution === 'cancelled' && activity.resolvedBy === SYSTEM_TIMEOUT_RESOLVED_BY
    return (
      <Card className="border-border/50 bg-muted/30 min-w-0 p-0">
        <CardContent className="flex min-w-0 items-center gap-2">
          {resolution === 'approved' ? (
            <CheckCircle2 className="h-4 w-4 shrink-0 text-green-500" />
          ) : timedOut ? (
            <Clock className="h-4 w-4 shrink-0 text-amber-500" />
          ) : resolution === 'cancelled' ? (
            <Ban className="text-muted-foreground h-4 w-4 shrink-0" />
          ) : (
            <XCircle className="h-4 w-4 shrink-0 text-red-500" />
          )}
          <span className="min-w-0 flex-1 truncate text-sm">
            {resolution === 'approved'
              ? 'Permission approved:'
              : timedOut
                ? 'Permission request timed out — no response:'
                : resolution === 'cancelled'
                  ? 'Permission request cancelled:'
                  : 'Permission rejected:'}{' '}
            <code className="bg-muted rounded px-1 font-mono text-xs">{command}</code>
          </span>
        </CardContent>
      </Card>
    )
  }

  const anyMark = hasAllowMark || hasDenyMark

  return (
    <Card className="min-w-0 border-amber-500/50 bg-amber-50 dark:bg-amber-950/20" ref={cardRef}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-amber-800 dark:text-amber-200">
          Permission Required
        </CardTitle>
      </CardHeader>
      <CardContent className="min-w-0 space-y-3">
        <div className="min-w-0 text-sm">
          <span className="text-muted-foreground">The agent wants to execute:</span>
          <pre className="bg-muted mt-1 overflow-x-auto rounded p-2 font-mono text-xs">
            {command}
          </pre>
        </div>

        {diff && (
          <div className="overflow-x-auto rounded bg-zinc-950 p-2 font-mono text-xs text-zinc-100">
            {diff.path && <div className="text-zinc-400">{diff.path}</div>}
            {diff.oldText && (
              <div className="whitespace-pre text-red-400 line-through">{diff.oldText}</div>
            )}
            {diff.newText && <div className="whitespace-pre text-green-400">{diff.newText}</div>}
          </div>
        )}

        {segments.length > 0 && (
          <div className="space-y-2">
            <Button
              aria-expanded={rememberOpen}
              className="w-full justify-between"
              onClick={() => setRememberOpen((open) => !open)}
              size="sm"
              type="button"
              variant="ghost"
            >
              <span>Remember choices</span>
              <ChevronDown
                className={cn('h-4 w-4 transition-transform', rememberOpen && 'rotate-180')}
              />
            </Button>
            {rememberOpen && (
              <div className="bg-background/60 space-y-3 rounded-md border p-2">
                <p className="text-muted-foreground text-xs">
                  Set rules for command roots. Allowed or blocked roots apply to all future commands
                  for this team.
                </p>
                {segments.map((segment, index) => (
                  <div
                    className="flex items-center gap-1.5 py-0.5"
                    key={`${hitlId}-segment-${index}`}
                  >
                    <Input
                      aria-label={`Command root: ${segment.suggestedRoot}`}
                      className="h-7 min-w-0 flex-1 py-0.5 font-mono text-xs leading-tight"
                      onChange={(event) =>
                        setRoots((prev) => ({ ...prev, [index]: event.target.value }))
                      }
                      value={roots[index] ?? segment.suggestedRoot}
                    />
                    <Button
                      aria-label={`Always allow ${segment.suggestedRoot}`}
                      aria-pressed={marks[index] === 'allow'}
                      className={cn(
                        'h-7 w-7 shrink-0 p-0',
                        marks[index] === 'allow'
                          ? 'border-green-600 bg-green-50 text-green-600 dark:bg-green-950/30'
                          : 'text-muted-foreground hover:text-foreground',
                      )}
                      disabled={isSubmitting}
                      onClick={() => toggleMark(index, 'allow')}
                      size="sm"
                      type="button"
                      variant="outline"
                    >
                      <Check className="h-4 w-4" />
                    </Button>
                    <Button
                      aria-label={`Always block ${segment.suggestedRoot}`}
                      aria-pressed={marks[index] === 'deny'}
                      className={cn(
                        'h-7 w-7 shrink-0 p-0',
                        marks[index] === 'deny'
                          ? 'border-red-600 bg-red-50 text-red-600 dark:bg-red-950/30'
                          : 'text-muted-foreground hover:text-foreground',
                      )}
                      disabled={isSubmitting}
                      onClick={() => toggleMark(index, 'deny')}
                      size="sm"
                      type="button"
                      variant="outline"
                    >
                      <X className="h-4 w-4" />
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        <HitlFeedbackField
          disabled={isSubmitting}
          id={`${hitlId}-feedback`}
          onChange={setFeedback}
          placeholder="Guidance sent with your response, e.g. use pnpm instead of npm"
          value={feedback}
        />

        <div className="flex gap-2">
          {allowOption && (
            <Button
              className="flex-1"
              disabled={isSubmitting || hasDenyMark || !allowOption}
              onClick={() => {
                void handleAllow()
              }}
              size="sm"
              variant="default"
            >
              <ShieldCheck className="h-4 w-4" />
              {hasAllowMark && !hasDenyMark ? 'Allow and remember' : optionLabel(allowOption)}
            </Button>
          )}
          {rejectOption && (
            <Button
              className="flex-1"
              disabled={isSubmitting || !rejectOption}
              onClick={() => {
                void handleReject()
              }}
              size="sm"
              variant="destructive"
            >
              <ShieldX className="h-4 w-4" />
              {anyMark ? 'Reject and remember' : optionLabel(rejectOption)}
            </Button>
          )}
          <Button
            className="flex-1"
            disabled={isSubmitting}
            onClick={() => {
              void handleCancel()
            }}
            size="sm"
            variant="outline"
          >
            Cancel
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

/** Always-variants are relabeled: persistent memory belongs to team rules, not the agent. */
function optionLabel(option: PermissionOption): string {
  if (option.kind === 'allow_always') return 'Allow once'
  if (option.kind === 'reject_always') return 'Reject'
  return option.name
}
