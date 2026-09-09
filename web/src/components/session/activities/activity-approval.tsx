import { CheckCircle2, ShieldCheck, ShieldX, XCircle } from 'lucide-react'
import { useState } from 'react'

import type {
  CommandExecutionActivity,
  PermissionOption,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { activityCommand, useActivityStore } from '@/store/activity-store'

interface ActivityApprovalProps {
  activity: ApprovalActivity
  executionId: string
}

type ApprovalActivity = CommandExecutionActivity | ToolExecutionActivity

export function ActivityApproval({ activity, executionId }: ActivityApprovalProps) {
  const [isSubmitting, setIsSubmitting] = useState(false)
  const resolveHitl = useActivityStore((s) => s.resolveHitl)
  const command = activityCommand(activity)
  const options = activity.permissionOptions ?? []
  const diff = activity.permissionDiff ?? activity.detail?.diff
  const hitlId = activity.actionId ?? command

  const isResolved =
    activity.state === 'active' || activity.state === 'error' || activity.state === 'completed'

  const handleOption = async (optionId: string) => {
    setIsSubmitting(true)
    try {
      const isAllow = options.find((o) => o.optionId === optionId)?.kind.startsWith('allow')
      const response = isAllow ? 'approved' : 'declined'
      await resolveHitl(executionId, hitlId, response, optionId)
    } catch {
      // handled via websocket resolution
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleCancel = async () => {
    setIsSubmitting(true)
    try {
      await resolveHitl(executionId, hitlId, 'cancelled')
    } catch {
      // handled via websocket resolution
    } finally {
      setIsSubmitting(false)
    }
  }

  if (isResolved) {
    const wasApproved = activity.approved === true
    return (
      <Card className="border-border/50 bg-muted/30 min-w-0 p-0">
        <CardContent className="flex min-w-0 items-center gap-2">
          {wasApproved ? (
            <CheckCircle2 className="h-4 w-4 shrink-0 text-green-500" />
          ) : (
            <XCircle className="h-4 w-4 shrink-0 text-red-500" />
          )}
          <span className="min-w-0 flex-1 truncate text-sm">
            Permission {wasApproved ? 'approved' : 'rejected'}:{' '}
            <code className="bg-muted rounded px-1 font-mono text-xs">{command}</code>
          </span>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="min-w-0 border-amber-500/50 bg-amber-50 dark:bg-amber-950/20">
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

        <div className="flex flex-wrap gap-2">
          {options.map((option) => (
            <OptionButton
              disabled={isSubmitting}
              key={option.optionId}
              onClick={() => {
                void handleOption(option.optionId)
              }}
              option={option}
            />
          ))}
        </div>

        <Button
          className="w-full"
          disabled={isSubmitting}
          onClick={() => {
            void handleCancel()
          }}
          size="sm"
          variant="outline"
        >
          Cancel
        </Button>
      </CardContent>
    </Card>
  )
}

function OptionButton({
  disabled,
  onClick,
  option,
}: {
  disabled: boolean
  onClick: () => void
  option: PermissionOption
}) {
  const isAllow = option.kind === 'allow_once' || option.kind === 'allow_always'
  const Icon = isAllow ? ShieldCheck : ShieldX
  return (
    <Button
      className="flex-1"
      disabled={disabled}
      onClick={onClick}
      size="sm"
      variant={isAllow ? 'default' : 'destructive'}
    >
      <Icon className="h-4 w-4" />
      {option.name}
    </Button>
  )
}
