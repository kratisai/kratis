import { useQuery } from '@tanstack/react-query'

import type { ChatUsageDto } from '@/lib/chat-api'

import { fetchChatUsage } from '@/lib/chat-api'
import { useAuthStore } from '@/store/auth-store'

export function useChatUsage(chatId: null | string) {
  const teamId = useAuthStore((state) => state.currentTeamId)
  return useQuery<ChatUsageDto>({
    enabled: !!teamId && !!chatId,
    queryFn: () => fetchChatUsage(teamId!, chatId!),
    queryKey: ['chat-usage', teamId, chatId],
  })
}
