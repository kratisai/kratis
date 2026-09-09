import { useNavigate, useParams, useSearch } from '@tanstack/react-router'
import {
  Activity as ActivityIcon,
  Check,
  ChevronDown,
  FileText,
  GitCompare,
  MessageSquare,
} from 'lucide-react'

import type { ExecutionStatus } from '@/lib/execution-api'
import type { CanvasDocument } from '@/types/canvas-types'

import { AgentBrandIcon } from '@/components/session/agent-brand-icon'
import { harnessDisplayName } from '@/components/session/execution-harness-badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useChatExecutions } from '@/hooks/use-executions'
import { cn } from '@/lib/utils'
import { useCanvasStore } from '@/store/canvas-store'

const EMPTY_CANVASES: CanvasDocument[] = []

export function MobileTopicSelector() {
  const navigate = useNavigate()
  const params = useParams({ strict: false })
  const search: { tab?: string } = useSearch({ strict: false })

  const chatId = params.id
  const docId = params.docId
  const executionId = params.executionId

  const canvases = useCanvasStore((state) =>
    chatId ? (state.canvases[chatId] ?? EMPTY_CANVASES) : EMPTY_CANVASES,
  )
  const { data: executions = [] } = useChatExecutions(chatId || '')

  if (!chatId) {
    return null
  }

  const activeDoc = canvases.find((d) => d.documentId === docId)
  const activeExecutionIndex = executions.findIndex((e) => e.id === executionId)

  // Derive active label for mobile trigger
  let activeLabel = 'Design & Plan'
  let ActiveIcon = MessageSquare

  if (executionId) {
    const runNum = activeExecutionIndex >= 0 ? activeExecutionIndex + 1 : 1
    if (search.tab === 'changes') {
      activeLabel = `Run ${runNum} · Changes`
      ActiveIcon = GitCompare
    } else {
      activeLabel = `Run ${runNum} · Logs`
      ActiveIcon = ActivityIcon
    }
  } else if (docId && activeDoc) {
    activeLabel = activeDoc.title
    ActiveIcon = FileText
  }

  const orderedExecutions = executions
    .map((exec, idx) => ({ ...exec, runNumber: idx + 1 }))
    .reverse()

  const navigateToChat = () => {
    void navigate({
      params: { id: chatId },
      to: '/chats/$id',
    })
  }

  const navigateToDoc = (targetDocId: string) => {
    void navigate({
      params: { docId: targetDocId, id: chatId },
      to: '/chats/$id/canvas/$docId',
    })
  }

  const navigateToExecution = (targetExecId: string, tab: 'activity' | 'changes') => {
    void navigate({
      params: { executionId: targetExecId, id: chatId },
      search: tab === 'changes' ? { tab: 'changes' } : {},
      to: '/chats/$id/executions/$executionId',
    })
  }

  return (
    <div className="flex items-center md:hidden" data-testid="mobile-topic-selector">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            aria-label="Topic navigation menu"
            className="hover:bg-accent/80 flex h-8 max-w-[170px] items-center gap-1.5 px-2 text-xs font-semibold sm:max-w-[240px]"
            size="sm"
            variant="ghost"
          >
            <ActiveIcon className="text-primary h-3.5 w-3.5 shrink-0" />
            <span className="truncate">{activeLabel}</span>
            <ChevronDown className="h-3 w-3 shrink-0 opacity-60" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="max-h-[80vh] w-64 overflow-y-auto">
          {/* Design & Plan Group */}
          <DropdownMenuLabel className="text-muted-foreground text-[10px] font-semibold tracking-wider uppercase">
            Design & Plan
          </DropdownMenuLabel>
          <DropdownMenuItem onClick={navigateToChat}>
            <MessageSquare className="text-primary mr-2 h-3.5 w-3.5" />
            <span className="flex-1 truncate">Chat Conversation</span>
            {!executionId && !docId && <Check className="text-primary ml-1 h-3.5 w-3.5 shrink-0" />}
          </DropdownMenuItem>

          {canvases.map((doc) => (
            <DropdownMenuItem key={doc.documentId} onClick={() => navigateToDoc(doc.documentId)}>
              <FileText className="text-primary mr-2 h-3.5 w-3.5" />
              <span className="flex-1 truncate">{doc.title}</span>
              {docId === doc.documentId && (
                <Check className="text-primary ml-1 h-3.5 w-3.5 shrink-0" />
              )}
            </DropdownMenuItem>
          ))}

          {/* Executions Group (ordered backwards: most recent first) */}
          {orderedExecutions.length > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuLabel className="text-muted-foreground text-[10px] font-semibold tracking-wider uppercase">
                Executions ({orderedExecutions.length})
              </DropdownMenuLabel>
              {orderedExecutions.map((exec) => {
                const isCurrentExec = executionId === exec.id
                return (
                  <div className="py-1" key={exec.id}>
                    <div className="text-muted-foreground flex items-center justify-between px-2 py-0.5 text-[11px] font-medium">
                      <span className="flex items-center gap-1.5">
                        <ExecutionStatusDot status={exec.status} />
                        Run {exec.runNumber}
                      </span>
                      <span className="flex items-center gap-1 font-mono text-[10px] opacity-80">
                        <AgentBrandIcon className="h-2.5 w-2.5" harness={exec.harness} />
                        <span>{harnessDisplayName(exec.harness ?? '')}</span>
                      </span>
                    </div>
                    <div className="space-y-0.5 pl-3">
                      <DropdownMenuItem
                        className="h-7 text-xs"
                        onClick={() => navigateToExecution(exec.id, 'activity')}
                      >
                        <ActivityIcon className="mr-2 h-3 w-3" />
                        <span className="flex-1">Activity Log</span>
                        {isCurrentExec && search.tab !== 'changes' && (
                          <Check className="text-primary ml-1 h-3.5 w-3.5 shrink-0" />
                        )}
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        className="h-7 text-xs"
                        onClick={() => navigateToExecution(exec.id, 'changes')}
                      >
                        <GitCompare className="mr-2 h-3 w-3" />
                        <span className="flex-1">Changes</span>
                        {isCurrentExec && search.tab === 'changes' && (
                          <Check className="text-primary ml-1 h-3.5 w-3.5 shrink-0" />
                        )}
                      </DropdownMenuItem>
                    </div>
                  </div>
                )
              })}
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function ExecutionStatusDot({ status }: { status: ExecutionStatus }) {
  const dotColor =
    status === 'RUNNING'
      ? 'bg-emerald-500 animate-pulse'
      : status === 'FAILED'
        ? 'bg-rose-500'
        : 'bg-emerald-600'
  return <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', dotColor)} />
}
