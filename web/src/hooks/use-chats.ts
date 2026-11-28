import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import {
  archiveChatApi,
  type ChatDto,
  createChatApi,
  type CreateChatParams,
  type CreateChatResponse,
  fetchChat,
  fetchChats,
  unarchiveChatApi,
  updateChatPermissionsApi,
} from '@/lib/chat-api'
import { useAuthStore } from '@/store/auth-store'

interface UpdateChatPermissionsVariables {
  allowAllPermissions: boolean
  chatId: string
}

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

export function useChat(teamId: null | string | undefined, chatId: null | string | undefined) {
  return useQuery<ChatDto>({
    enabled: !!teamId && !!chatId,
    queryFn: () => fetchChat(teamId!, chatId!),
    queryKey: ['chats', teamId, 'detail', chatId],
  })
}

export function useChatPermissions(chatId: null | string | undefined): boolean {
  const teamId = useAuthStore((state) => state.currentTeamId)
  const { data: chat } = useChat(teamId, chatId)
  return chat?.allowAllPermissions ?? false
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

export function useUpdateChatPermissions(teamId: null | string | undefined) {
  const queryClient = useQueryClient()
  return useMutation<void, Error, UpdateChatPermissionsVariables>({
    mutationFn: ({ allowAllPermissions, chatId }) =>
      updateChatPermissionsApi(chatId, allowAllPermissions),
    onSuccess: () => {
      if (teamId) {
        void queryClient.invalidateQueries({ queryKey: ['chats', teamId] })
      }
    },
  })
}
