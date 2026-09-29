import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import {
  archiveChatApi,
  type ChatDto,
  createChatApi,
  type CreateChatParams,
  type CreateChatResponse,
  fetchChats,
  unarchiveChatApi,
} from '@/lib/chat-api'

export function useArchiveChat(teamId: null | string | undefined) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: archiveChatApi,
    onSuccess: () => {
      if (teamId) {
        void queryClient.invalidateQueries({ queryKey: ['chats', teamId] })
      }
    },
  })
}

export function useChats(
  teamId: null | string | undefined,
  filter: 'all' | 'mine' = 'mine',
  status: 'active' | 'all' | 'archived' = 'active',
) {
  return useQuery<ChatDto[]>({
    enabled: !!teamId,
    queryFn: () => fetchChats(teamId!, filter, status),
    queryKey: ['chats', teamId, filter, status],
  })
}

export function useCreateChat() {
  const queryClient = useQueryClient()
  return useMutation<CreateChatResponse, Error, CreateChatParams>({
    mutationFn: createChatApi,
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['chats', variables.teamId] })
    },
  })
}

export function useUnarchiveChat(teamId: null | string | undefined) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: unarchiveChatApi,
    onSuccess: () => {
      if (teamId) {
        void queryClient.invalidateQueries({ queryKey: ['chats', teamId] })
      }
    },
  })
}
