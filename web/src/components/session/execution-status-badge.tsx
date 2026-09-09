import { CheckCircle2, Loader2, XCircle } from 'lucide-react'

import type { ExecutionStatus } from '@/lib/execution-api'

import { Badge } from '@/components/ui/badge'

interface ExecutionStatusBadgeProps {
  status: ExecutionStatus
}

export function ExecutionStatusBadge({ status }: ExecutionStatusBadgeProps) {
  if (status === 'RUNNING') {
    return (
      <Badge className="gap-1 text-emerald-600 dark:text-emerald-400" variant="outline">
        <Loader2 className="h-3 w-3 animate-spin" />
        Active
      </Badge>
    )
  }
  if (status === 'FAILED') {
    return (
      <Badge className="gap-1 text-red-600 dark:text-red-400" variant="outline">
        <XCircle className="h-3 w-3" />
        Failed
      </Badge>
    )
  }
  return (
    <Badge className="gap-1 text-green-600 dark:text-green-400" variant="outline">
      <CheckCircle2 className="h-3 w-3" />
      Complete
    </Badge>
  )
}
