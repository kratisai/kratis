import { Loader2 } from 'lucide-react'

import type { EnvironmentStatus } from '@/types/websocket-types'

import { Button } from '@/components/ui/button'

interface TerminalLogNoticeProps {
  isWaking: boolean
  onWake: (() => void) | null
  status: EnvironmentStatus | undefined
}

export function TerminalLogNotice({ isWaking, onWake, status }: TerminalLogNoticeProps) {
  if (status === 'SLEEPING') {
    return (
      <div
        className="mb-3 flex items-center gap-3 rounded border border-zinc-800 bg-zinc-900 px-3 py-2 text-zinc-300"
        data-testid="terminal-sleeping-notice"
      >
        <span>Sandbox is asleep.</span>
        {onWake && (
          <Button
            className="h-6 px-2 text-[11px]"
            disabled={isWaking}
            onClick={onWake}
            size="sm"
            variant="secondary"
          >
            {isWaking && <Loader2 className="mr-1 h-3 w-3 animate-spin" />}
            Wake Sandbox to View Console
          </Button>
        )}
      </div>
    )
  }
  if (status === 'TERMINATED') {
    return (
      <div
        className="mb-3 rounded border border-zinc-800 bg-zinc-900 px-3 py-2 text-zinc-300"
        data-testid="terminal-terminated-notice"
      >
        Sandbox was terminated and cannot be woken. Console output is gone; the stored diffs remain
        available in the execution view.
      </div>
    )
  }
  if (status === 'PENDING_RECONNECT') {
    return (
      <div
        className="mb-3 flex items-center gap-2 rounded border border-zinc-800 bg-zinc-900 px-3 py-2 text-zinc-400"
        data-testid="terminal-pending-reconnect-notice"
      >
        <Loader2 className="h-3 w-3 animate-spin" />
        <span>Sandbox is starting. Console history loads once it reconnects.</span>
      </div>
    )
  }
  if (status === 'DISCONNECTED') {
    return (
      <div
        className="mb-3 rounded border border-zinc-800 bg-zinc-900 px-3 py-2 text-zinc-400"
        data-testid="terminal-disconnected-notice"
      >
        Sandbox is disconnected. Console history is unavailable until it reconnects.
      </div>
    )
  }
  return null
}
