import { useChatUsage } from '@/hooks/use-chat-usage'
import { formatRelativeTime, formatSpend } from '@/lib/format'
import { cn } from '@/lib/utils'

interface ChatUsageSummaryProps {
  chatId: string
  className?: string
}

export function ChatUsageSummary({ chatId, className }: ChatUsageSummaryProps) {
  const { data: usage } = useChatUsage(chatId)
  const totalSpend = usage?.totalSpend
  const totalTokens = usage?.totalTokens

  if (totalSpend == null && totalTokens == null) {
    return null
  }

  const title = [
    `Prompt tokens: ${formatTokens(usage?.promptTokens)}`,
    `Completion tokens: ${formatTokens(usage?.completionTokens)}`,
    usage?.usageLastUpdatedAt ? `Updated ${formatRelativeTime(usage.usageLastUpdatedAt)}` : null,
  ]
    .filter(Boolean)
    .join('\n')

  return (
    <div
      className={cn('text-muted-foreground flex items-center gap-4 text-xs', className)}
      data-testid="chat-usage-summary"
      title={title}
    >
      <span>
        Cost: <span className="text-foreground font-medium">{formatChatSpend(totalSpend)}</span>
      </span>
      <span>
        Tokens: <span className="text-foreground font-medium">{formatTokens(totalTokens)}</span>
      </span>
    </div>
  )
}

function formatChatSpend(value: null | number | undefined): string {
  if (value == null) return '—'
  if (value < 0.01) {
    return new Intl.NumberFormat('en-US', {
      currency: 'USD',
      maximumFractionDigits: 4,
      minimumFractionDigits: 4,
      style: 'currency',
    }).format(value)
  }
  return formatSpend(value)
}

function formatTokens(value: null | number | undefined): string {
  if (value == null) return '—'
  return new Intl.NumberFormat('en-US').format(value)
}
