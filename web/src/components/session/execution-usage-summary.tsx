import { useChatExecutions } from '@/hooks/use-executions'
import { formatSpend } from '@/lib/format'

interface ExecutionUsageSummaryProps {
  chatId: string
  executionId: string
}

export function ExecutionUsageSummary({ chatId, executionId }: ExecutionUsageSummaryProps) {
  const { data: executions = [] } = useChatExecutions(chatId)
  const execution = executions.find((e) => e.id === executionId)
  const totalSpend = execution?.totalSpend
  const totalTokens = execution?.totalTokens

  if (totalSpend == null && totalTokens == null) {
    return null
  }

  return (
    <div
      className="text-muted-foreground flex items-center gap-4 text-xs"
      data-testid="execution-usage-summary"
    >
      <span>
        Cost: <span className="text-foreground font-medium">{formatSpend(totalSpend)}</span>
      </span>
      <span>
        Tokens: <span className="text-foreground font-medium">{formatTokens(totalTokens)}</span>
      </span>
    </div>
  )
}

function formatTokens(value: null | number | undefined): string {
  if (value == null) return '—'
  return new Intl.NumberFormat('en-US').format(value)
}
