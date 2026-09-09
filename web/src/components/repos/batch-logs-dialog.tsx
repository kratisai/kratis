import { AlertCircle, CheckCircle2, Loader2, Play, Terminal } from 'lucide-react'
import { useEffect, useRef } from 'react'

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ScrollArea } from '@/components/ui/scroll-area'
import { useBatchLogs, useIngestionStatus } from '@/hooks/use-repositories'

interface BatchLogsDialogProps {
  isOpen: boolean
  onClose: () => void
  repoId: string
  repoName: string
}

export function BatchLogsDialog({ isOpen, onClose, repoId, repoName }: BatchLogsDialogProps) {
  const { data: statusData, isLoading: isStatusLoading } = useIngestionStatus(repoId, isOpen)
  const batchId = statusData?.batchId ?? null
  const { data: logs = [], isLoading: isLogsLoading } = useBatchLogs(repoId, batchId)

  const consoleEndRef = useRef<HTMLDivElement>(null)

  // Auto-scroll to the bottom of the log viewer when new logs arrive
  useEffect(() => {
    if (consoleEndRef.current) {
      consoleEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [logs])

  const activeStatus = statusData?.status ?? 'INACTIVE'
  const isRunning = activeStatus === 'QUEUED' || activeStatus === 'PROCESSING'

  return (
    <Dialog onOpenChange={(open) => !open && onClose()} open={isOpen}>
      <DialogContent className="border-border bg-background/95 max-w-3xl shadow-2xl backdrop-blur-md">
        <DialogHeader className="pb-2">
          <div className="flex items-center gap-3">
            <div className="bg-primary/10 text-primary border-primary/20 flex h-10 w-10 items-center justify-center rounded-xl border">
              <Terminal className="h-5 w-5 animate-pulse" />
            </div>
            <div>
              <DialogTitle className="text-xl font-bold tracking-tight">
                Repository Research Logs
              </DialogTitle>
              <DialogDescription className="text-muted-foreground mt-0.5">
                Real-time ingestion and agentic research logs for {repoName}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <div className="space-y-4 py-2">
          {/* Active Status bar */}
          <div className="border-border/80 bg-accent/30 flex items-center justify-between rounded-xl border p-4">
            <div className="flex items-center gap-3">
              {isRunning ? (
                <Loader2 className="text-primary h-5 w-5 animate-spin" />
              ) : activeStatus === 'SUCCESS' ? (
                <CheckCircle2 className="h-5 w-5 text-green-500" />
              ) : activeStatus === 'FAILED' ? (
                <AlertCircle className="text-destructive h-5 w-5" />
              ) : (
                <Play className="text-muted-foreground h-5 w-5" />
              )}
              <div>
                <p className="text-sm font-semibold tracking-wide">
                  Ingestion Status: <span className="text-primary font-bold">{activeStatus}</span>
                </p>
                {statusData?.commitHash && (
                  <p className="text-muted-foreground mt-0.5 font-mono text-xs">
                    Commit: {statusData.commitHash}
                  </p>
                )}
              </div>
            </div>
            {isRunning && (
              <span className="relative flex h-2 w-2">
                <span className="bg-primary absolute inline-flex h-full w-full animate-ping rounded-full opacity-75"></span>
                <span className="bg-primary relative inline-flex h-2 w-2 rounded-full"></span>
              </span>
            )}
          </div>

          {/* Terminal Logs Viewer */}
          <div className="relative rounded-2xl border border-black/10 bg-slate-950 p-4 shadow-inner">
            <div className="mb-3 flex items-center justify-between border-b border-slate-800 pb-2">
              <div className="flex items-center gap-1.5">
                <div className="h-3.5 w-3.5 rounded-full bg-rose-500/80" />
                <div className="h-3.5 w-3.5 rounded-full bg-amber-500/80" />
                <div className="h-3.5 w-3.5 rounded-full bg-emerald-500/80" />
              </div>
              <span className="font-mono text-xs text-slate-500 select-none">
                kratis-intel-session
              </span>
            </div>

            <ScrollArea className="h-[320px] w-full font-mono text-xs text-slate-300">
              {isStatusLoading ? (
                <div className="flex h-[280px] flex-col items-center justify-center gap-2 text-slate-500">
                  <Loader2 className="h-6 w-6 animate-spin text-slate-600" />
                  <span>Loading ingestion session...</span>
                </div>
              ) : !batchId ? (
                <div className="flex h-[280px] flex-col items-center justify-center gap-2 text-slate-500 select-none">
                  <Terminal className="mb-1 h-8 w-8 text-slate-700" />
                  <span>No active research batch logs found for this repository.</span>
                  <span className="text-[10px] text-slate-600">
                    Trigger Ingestion to see agentic logs
                  </span>
                </div>
              ) : isLogsLoading && logs.length === 0 ? (
                <div className="flex h-[280px] flex-col items-center justify-center gap-2 text-slate-500">
                  <Loader2 className="h-6 w-6 animate-spin text-slate-600" />
                  <span>Fetching logs...</span>
                </div>
              ) : logs.length > 0 ? (
                <div className="space-y-1.5 pr-3">
                  {logs.map((log) => {
                    const isError = log.level === 'ERROR'
                    const dateStr = new Date(log.createdAt).toLocaleTimeString()
                    return (
                      <div
                        className="flex items-start gap-2 leading-relaxed break-all"
                        key={log.id}
                      >
                        <span className="shrink-0 text-slate-600 select-none">{dateStr}</span>
                        <span
                          className={`shrink-0 rounded px-1 py-0.5 text-[10px] font-semibold select-none ${
                            isError ? 'bg-red-950 text-red-400' : 'bg-blue-950 text-blue-400'
                          }`}
                        >
                          {log.level}
                        </span>
                        <span className="shrink-0 text-emerald-500 select-none">[{log.step}]</span>
                        <span className={isError ? 'text-rose-300' : 'text-slate-200'}>
                          {log.message}
                        </span>
                      </div>
                    )
                  })}
                  <div ref={consoleEndRef} />
                </div>
              ) : (
                <div className="flex h-[280px] flex-col items-center justify-center gap-2 text-slate-500 select-none">
                  <Terminal className="mb-1 h-8 w-8 animate-pulse text-slate-700" />
                  <span>Initializing research logs terminal...</span>
                </div>
              )}
            </ScrollArea>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
