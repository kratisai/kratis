import { CheckCircle2, CircleDashed, Clock, Loader2, OctagonAlert } from 'lucide-react'

import type { IngestionStatus } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'

interface IngestionStatusBadgeProps {
  queuePosition?: null | number
  status?: IngestionStatus | null
}

const statusConfig: Record<
  NonNullable<IngestionStatus>,
  { className: string; icon: React.ReactNode; label: string }
> = {
  FAILED: {
    className:
      'bg-red-100 text-red-800 border-red-200 dark:bg-red-900/30 dark:text-red-300 dark:border-red-800',
    icon: <OctagonAlert className="h-3 w-3" />,
    label: 'Failed',
  },
  PROCESSING: {
    className:
      'bg-blue-100 text-blue-800 border-blue-200 dark:bg-blue-900/30 dark:text-blue-300 dark:border-blue-800',
    icon: <Loader2 className="h-3 w-3 animate-spin" />,
    label: 'Processing',
  },
  QUEUED: {
    className:
      'bg-yellow-100 text-yellow-800 border-yellow-200 dark:bg-yellow-900/30 dark:text-yellow-300 dark:border-yellow-800',
    icon: <Clock className="h-3 w-3" />,
    label: 'Queued',
  },
  SUCCESS: {
    className:
      'bg-green-100 text-green-800 border-green-200 dark:bg-green-900/30 dark:text-green-300 dark:border-green-800',
    icon: <CheckCircle2 className="h-3 w-3" />,
    label: 'Success',
  },
}

export function IngestionStatusBadge({ queuePosition, status }: IngestionStatusBadgeProps) {
  if (!status) {
    return (
      <Badge
        className="border-gray-200 bg-gray-100 text-gray-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
        variant="outline"
      >
        <CircleDashed className="h-3 w-3" />
        Not Ingested
      </Badge>
    )
  }

  const config = statusConfig[status]

  const label = status === 'QUEUED' && queuePosition ? `Queued (#${queuePosition})` : config.label

  return (
    <Badge className={`gap-1.5 ${config.className}`} variant="outline">
      {config.icon}
      {label}
    </Badge>
  )
}
