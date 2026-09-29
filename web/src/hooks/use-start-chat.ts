import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'

import { useCreateChat } from '@/hooks/use-chats'
import { useAuthStore } from '@/store/auth-store'
import { useUIStore } from '@/store/ui-store'

export function useStartChat() {
  const navigate = useNavigate()
  const createChat = useCreateChat()
  const currentTeamId = useAuthStore((state) => state.currentTeamId)
  const selectedProviderId = useUIStore((state) => state.selectedProviderId)
  const selectedModelName = useUIStore((state) => state.selectedModelName)

  const startChat = (message: string) => {
    if (!message.trim()) return

    if (!currentTeamId) {
      toast.error('No team selected')
      return
    }
    if (!selectedProviderId) {
      toast.error('No model provider selected')
      return
    }
    if (!selectedModelName) {
      toast.error('No model selected')
      return
    }

    createChat.mutate(
      {
        message: message.trim(),
        modelName: selectedModelName,
        providerId: selectedProviderId,
        teamId: currentTeamId,
      },
      {
        onError: (err) => {
          toast.error(err.message || 'Failed to start chat')
        },
        onSuccess: (data) => {
          void navigate({ params: { id: data.id } as never, to: '/chats/$id' })
        },
      },
    )
  }

  return {
    isCreating: createChat.isPending,
    startChat,
  }
}
