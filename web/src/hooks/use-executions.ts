import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { SandboxExecutionDto } from '@/lib/execution-api'

import { haltExecution, listChatExecutions } from '@/lib/execution-api'

const CHAT_EXECUTIONS_QUERY_KEY = 'chat-executions'
const ENVIRONMENTS_QUERY_KEY = 'environments'

export function useChatExecutions(chatId: null | string) {
  return useQuery<SandboxExecutionDto[]>({
    enabled: !!chatId,
    queryFn: () => listChatExecutions(chatId!),
    queryKey: [CHAT_EXECUTIONS_QUERY_KEY, chatId],
  })
}

export function useHaltExecution() {
  const queryClient = useQueryClient()

  return useMutation<void, Error, { chatId: string; executionId: string }>({
    mutationFn: ({ chatId, executionId }) => haltExecution(chatId, executionId),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to halt execution')
    },
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({
        queryKey: [CHAT_EXECUTIONS_QUERY_KEY, variables.chatId],
      })
      void queryClient.invalidateQueries({ queryKey: [ENVIRONMENTS_QUERY_KEY] })
      toast.success('Execution halted')
    },
  })
}
