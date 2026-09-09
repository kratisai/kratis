import { useQuery } from '@tanstack/react-query'

import type { SandboxExecutionDto } from '@/lib/execution-api'

import { listChatExecutions } from '@/lib/execution-api'

const CHAT_EXECUTIONS_QUERY_KEY = 'chat-executions'

export function useChatExecutions(chatId: null | string) {
  return useQuery<SandboxExecutionDto[]>({
    enabled: !!chatId,
    queryFn: () => listChatExecutions(chatId!),
    queryKey: [CHAT_EXECUTIONS_QUERY_KEY, chatId],
  })
}
