import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ListTodo,
  Loader2,
  Terminal,
  XCircle,
} from 'lucide-react'
import { memo, type ReactNode, useEffect, useMemo, useRef } from 'react'

import type {
  Activity,
  CommandExecutionActivity,
  ElicitationActivity,
  ErrorActivity,
  MessageActivity,
  PlanActivity,
  ThinkingActivity,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'

import { ActivityApproval } from '@/components/session/activities/activity-approval'
import { ActivityElicitation } from '@/components/session/activities/activity-elicitation'
import { PlanEntriesList, planSummaryLabel } from '@/components/session/plan-entries'
import { activityTitle, useActivityStore } from '@/store/activity-store'

const EMPTY_ACTIVITIES: Activity[] = []

const NEAR_BOTTOM_THRESHOLD_PX = 120

interface ActivityItemProps {
  activity: Activity
  executionId: string
}

interface ActivityItemShellProps {
  activity: Activity
  content: ReactNode
  executionId: string
  icon: ReactNode
  title: string
  trailing?: ReactNode
}

interface ExecutionActivityLogProps {
  executionId: string
}

export function ExecutionActivityLog({ executionId }: ExecutionActivityLogProps) {
  const activities = useActivityStore(
    (state) => state.activitiesByExecution[executionId] ?? EMPTY_ACTIVITIES,
  )
  const logRef = useRef<HTMLDivElement>(null)
  const logEndRef = useRef<HTMLDivElement>(null)
  const isNearBottomRef = useRef(true)
  const hasActivities = activities.length > 0

  const hitlActivityId = useMemo(
    () =>
      activities.findLast(
        (activity) =>
          activity.state === 'pending_approval' ||
          (activity.type === 'elicitation' && activity.state === 'active'),
      )?.id,
    [activities],
  )

  useEffect(() => {
    const scroller =
      getScrollParent(logRef.current) ?? document.scrollingElement ?? document.documentElement

    const updateNearBottom = () => {
      const distanceFromBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight
      isNearBottomRef.current = distanceFromBottom < NEAR_BOTTOM_THRESHOLD_PX
    }
    updateNearBottom()
    scroller.addEventListener('scroll', updateNearBottom, { passive: true })
    return () => scroller.removeEventListener('scroll', updateNearBottom)
  }, [hasActivities])

  useEffect(() => {
    // Instant (not smooth) scrolling: smooth animations restart on every stream event and jump
    // against a moving target. Suppressed while a HITL prompt is open so it stays in view.
    if (!isNearBottomRef.current || hitlActivityId !== undefined) return
    logEndRef.current?.scrollIntoView({ behavior: 'auto', block: 'end' })
  }, [activities, hitlActivityId])

  useEffect(() => {
    // Approval requests often merge into an already-rendered activity, so no length change
    // would scroll the prompt into view.
    if (hitlActivityId === undefined || !logRef.current) return
    const prompt = logRef.current.querySelector(`[data-activity-id="${hitlActivityId}"]`)
    prompt?.scrollIntoView({ behavior: 'auto', block: 'center' })
  }, [hitlActivityId])

  if (activities.length === 0) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <p className="text-muted-foreground text-sm">No activity yet</p>
      </div>
    )
  }

  return (
    <div
      className="activity-log mx-auto w-full max-w-4xl min-w-0 space-y-1 p-2 sm:p-4"
      data-testid="execution-activity-log"
      ref={logRef}
    >
      {activities.map((activity) => (
        <div data-activity-id={activity.id} key={activity.id}>
          <ActivityItem activity={activity} executionId={executionId} />
        </div>
      ))}
      <div ref={logEndRef} />
    </div>
  )
}

/** Desktop scrolls the log pane; mobile scrolls the document. */
function getScrollParent(element: HTMLElement | null): HTMLElement | null {
  let node = element?.parentElement ?? null
  while (node) {
    const overflowY = window.getComputedStyle(node).overflowY
    if (overflowY === 'auto' || overflowY === 'scroll') return node
    node = node.parentElement
  }
  return null
}

const ActivityItem = memo(function ActivityItem({ activity, executionId }: ActivityItemProps) {
  switch (activity.type) {
    case 'command_execution':
      return <CommandExecutionActivityItem activity={activity} executionId={executionId} />
    case 'elicitation':
      return <ElicitationActivityItem activity={activity} executionId={executionId} />
    case 'error':
      return <ErrorActivityItem activity={activity} />
    case 'message':
      return <MessageActivityItem activity={activity} executionId={executionId} />
    case 'plan':
      return <PlanActivityItem activity={activity} executionId={executionId} />
    case 'thinking':
      return <ThinkingActivityItem activity={activity} executionId={executionId} />
    case 'tool_execution':
      return <ToolExecutionActivityItem activity={activity} executionId={executionId} />
  }
})

function ActivityItemShell({
  activity,
  content,
  executionId,
  icon,
  title,
  trailing,
}: ActivityItemShellProps) {
  const toggleCollapsed = useActivityStore((s) => s.toggleCollapsed)
  const collapsed = activity.collapsed

  const chevron = collapsed ? (
    <ChevronRight className="text-muted-foreground h-3 w-3 shrink-0" />
  ) : (
    <ChevronDown className="text-muted-foreground h-3 w-3 shrink-0" />
  )

  const toggle = () => toggleCollapsed(executionId, activity.id)

  if (collapsed) {
    return (
      <button
        className="hover:bg-muted/50 flex w-full min-w-0 items-center gap-2 rounded px-2 py-1 text-left text-sm"
        onClick={toggle}
        title={title}
        type="button"
      >
        {chevron}
        {icon}
        <span className="min-w-0 flex-1 truncate">{title}</span>
        {trailing}
      </button>
    )
  }

  return (
    <div className="border-border/50 min-w-0 rounded border p-2">
      <button
        className="flex w-full min-w-0 items-center gap-2 text-left text-sm"
        onClick={toggle}
        type="button"
      >
        {chevron}
        {icon}
        <span className="min-w-0 flex-1 truncate font-medium">{title}</span>
        {trailing}
      </button>
      {content}
    </div>
  )
}

function CommandExecutionActivityItem({
  activity,
  executionId,
}: {
  activity: CommandExecutionActivity
  executionId: string
}) {
  if (activity.state === 'pending_approval') {
    return <ActivityApproval activity={activity} executionId={executionId} />
  }

  if (activity.approvalRequired && (activity.state === 'active' || activity.state === 'error')) {
    return <ActivityApproval activity={activity} executionId={executionId} />
  }

  return (
    <ActivityItemShell
      activity={activity}
      content={
        activity.detail?.output ? (
          <div className="mt-1 overflow-x-auto rounded bg-zinc-950 p-2 font-mono text-xs text-zinc-100">
            <div className="whitespace-pre">{activity.detail.output}</div>
          </div>
        ) : undefined
      }
      executionId={executionId}
      icon={
        <>
          {statusIcon(activity)}
          <Terminal className="h-3 w-3 shrink-0" />
        </>
      }
      title={activityTitle(activity)}
      trailing={
        activity.exitCode !== undefined && (
          <span className="text-muted-foreground shrink-0 text-xs">
            (exit: {activity.exitCode})
          </span>
        )
      }
    />
  )
}

function ElicitationActivityItem({
  activity,
  executionId,
}: {
  activity: ElicitationActivity
  executionId: string
}) {
  return <ActivityElicitation activity={activity} executionId={executionId} />
}

function ErrorActivityItem({ activity }: { activity: ErrorActivity }) {
  return (
    <ActivityItemShell
      activity={activity}
      content={
        <div className="mt-1 space-y-1 pl-5">
          <p className="text-xs break-words whitespace-pre-wrap text-red-600 dark:text-red-400">
            {activity.message}
          </p>
          {activity.exitCode !== undefined && (
            <p className="text-muted-foreground text-xs">Exit code: {activity.exitCode}</p>
          )}
        </div>
      }
      executionId=""
      icon={<AlertTriangle className="h-3 w-3 shrink-0 text-red-500" />}
      title={activityTitle(activity)}
    />
  )
}

function MessageActivityItem({
  activity,
  executionId,
}: {
  activity: MessageActivity
  executionId: string
}) {
  return (
    <ActivityItemShell
      activity={activity}
      content={
        <div className="mt-1 pl-5">
          <p className="text-muted-foreground text-xs break-words whitespace-pre-wrap">
            {activity.text}
          </p>
        </div>
      }
      executionId={executionId}
      icon={<span className="text-muted-foreground shrink-0 text-xs font-medium uppercase">•</span>}
      title={activityTitle(activity)}
    />
  )
}

function PlanActivityItem({
  activity,
  executionId,
}: {
  activity: PlanActivity
  executionId: string
}) {
  return (
    <ActivityItemShell
      activity={activity}
      content={
        <div className="mt-1 pl-5">
          <PlanEntriesList entries={activity.plan} />
        </div>
      }
      executionId={executionId}
      icon={<ListTodo className="text-muted-foreground h-3 w-3 shrink-0" />}
      title={activityTitle(activity)}
      trailing={
        activity.plan.length > 0 && (
          <span className="text-muted-foreground shrink-0 text-xs">
            {planSummaryLabel(activity.plan)}
          </span>
        )
      }
    />
  )
}

function statusIcon(activity: Activity): ReactNode {
  if (activity.state === 'active' || activity.state === 'pending_approval') {
    return <Loader2 className="h-3 w-3 shrink-0 animate-spin text-blue-500" />
  }
  if (activity.state === 'error') {
    return <XCircle className="h-3 w-3 shrink-0 text-red-500" />
  }
  return <CheckCircle2 className="h-3 w-3 shrink-0 text-green-500" />
}

function ThinkingActivityItem({
  activity,
  executionId,
}: {
  activity: ThinkingActivity
  executionId: string
}) {
  return (
    <ActivityItemShell
      activity={activity}
      content={
        <div className="mt-1 pl-5">
          <p className="text-muted-foreground text-xs break-words whitespace-pre-wrap">
            {activity.thought}
          </p>
        </div>
      }
      executionId={executionId}
      icon={statusIcon(activity)}
      title={activityTitle(activity)}
    />
  )
}

function ToolExecutionActivityItem({
  activity,
  executionId,
}: {
  activity: ToolExecutionActivity
  executionId: string
}) {
  if (activity.state === 'pending_approval') {
    return <ActivityApproval activity={activity} executionId={executionId} />
  }

  if (activity.approvalRequired && (activity.state === 'active' || activity.state === 'error')) {
    return <ActivityApproval activity={activity} executionId={executionId} />
  }

  return (
    <ActivityItemShell
      activity={activity}
      content={
        (activity.thought ||
          activity.detail?.diff ||
          activity.detail?.output ||
          activity.result ||
          activity.error) && (
          <div className="mt-1 space-y-1 pl-5">
            {activity.thought && (
              <p className="text-muted-foreground text-xs break-words">{activity.thought}</p>
            )}
            {activity.detail?.diff && (
              <div className="overflow-x-auto rounded bg-zinc-950 p-2 font-mono text-xs text-zinc-100">
                {activity.detail.diff.path && (
                  <div className="text-zinc-400">{activity.detail.diff.path}</div>
                )}
                {activity.detail.diff.oldText && (
                  <div className="whitespace-pre text-red-400 line-through">
                    {activity.detail.diff.oldText}
                  </div>
                )}
                {activity.detail.diff.newText && (
                  <div className="whitespace-pre text-green-400">
                    {activity.detail.diff.newText}
                  </div>
                )}
              </div>
            )}
            {activity.detail?.output && activity.detail.output.length > 0 && (
              <div className="overflow-x-auto rounded bg-zinc-950 p-2 font-mono text-xs whitespace-pre text-zinc-100">
                {activity.detail.output}
              </div>
            )}
            {activity.result && (
              <p className="text-xs break-words text-green-600 dark:text-green-400">
                {activity.result}
              </p>
            )}
            {activity.error && (
              <p className="text-xs break-words text-red-600 dark:text-red-400">{activity.error}</p>
            )}
          </div>
        )
      }
      executionId={executionId}
      icon={statusIcon(activity)}
      title={activityTitle(activity)}
    />
  )
}
