import { ShieldCheck, ShieldOff } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { useChatPermissions, useUpdateChatPermissions } from '@/hooks/use-chats'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth-store'

interface AllowAllPermissionsToggleProps {
  chatId: string
  disabled?: boolean
}

/**
 * Topic-scoped switch that auto-approves every permission request in the chat. Applies to
 * requests that arrive after it is enabled; already-pending approvals are unaffected.
 */
export function AllowAllPermissionsToggle({ chatId, disabled }: AllowAllPermissionsToggleProps) {
  const teamId = useAuthStore((state) => state.currentTeamId)
  const allowAllPermissions = useChatPermissions(chatId)
  const updatePermissions = useUpdateChatPermissions(teamId)

  const handleToggle = () => {
    updatePermissions.mutate(
      { allowAllPermissions: !allowAllPermissions, chatId },
      { onError: () => toast.error('Failed to update permission mode') },
    )
  }

  return (
    <Button
      aria-label="Auto approve permission requests in this topic"
      aria-pressed={allowAllPermissions}
      className={cn(
        'h-7 gap-1.5 text-xs',
        allowAllPermissions
          ? 'border-amber-500/60 bg-amber-50 text-amber-700 dark:bg-amber-950/30 dark:text-amber-300'
          : 'text-muted-foreground hover:text-foreground',
      )}
      disabled={disabled || updatePermissions.isPending}
      onClick={handleToggle}
      size="sm"
      title="Auto approve permission requests in this topic"
      type="button"
      variant="outline"
    >
      {allowAllPermissions ? (
        <ShieldCheck className="h-3.5 w-3.5" />
      ) : (
        <ShieldOff className="h-3.5 w-3.5" />
      )}
      Auto approve
    </Button>
  )
}
