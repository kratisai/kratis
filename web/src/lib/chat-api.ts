import { fetchWithAuth } from '@/lib/auth-api'

export interface ChatDto {
  allowAllPermissions?: boolean
  id: string
  lastActivityAt?: null | string
  lastActivityTargetId?: null | string
  lastActivityType?: 'CANVAS' | 'CHAT' | 'EXECUTION'
  status?: TopicStatus
  title: string
}

export interface ChatUsageDto {
  completionTokens: null | number
  promptTokens: null | number
  totalSpend: null | number
  totalTokens: null | number
  usageLastUpdatedAt: null | string
}

export interface CreateChatParams {
  message: string
  modelName: string
  providerId: string
  teamId: string
}

export interface CreateChatResponse {
  createdAt: string
  id: string
}

export type TopicStatus =
  | 'Blocked'
  | 'Idle'
  | 'MR merged'
  | 'MR pushed'
  | 'Planning'
  | 'Ready to publish'
  | 'working'

export async function archiveChatApi(chatId: string): Promise<void> {
  const response = await fetchWithAuth(`/api/v1/chats/${encodeURIComponent(chatId)}/archive`, {
    method: 'POST',
  })
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || 'Failed to archive chat')
  }
}

export async function createChatApi(params: CreateChatParams): Promise<CreateChatResponse> {
  const response = await fetchWithAuth('/api/v1/chats', {
    body: JSON.stringify(params),
    headers: { 'Content-Type': 'application/json' },
    method: 'POST',
  })
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || 'Failed to create chat')
  }
  return response.json()
}

export async function fetchChat(teamId: string, chatId: string): Promise<ChatDto> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${encodeURIComponent(chatId)}?teamId=${encodeURIComponent(teamId)}`,
  )
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || 'Failed to fetch chat')
  }
  return response.json()
}

export async function fetchChats(
  teamId: string,
  filter: 'all' | 'mine',
  status: 'active' | 'all' | 'archived' = 'active',
): Promise<ChatDto[]> {
  const response = await fetchWithAuth(
    `/api/v1/chats?teamId=${encodeURIComponent(teamId)}&filter=${encodeURIComponent(filter)}&status=${encodeURIComponent(status)}`,
  )
  return response.json()
}

export async function fetchChatUsage(teamId: string, chatId: string): Promise<ChatUsageDto> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${encodeURIComponent(chatId)}/usage?teamId=${encodeURIComponent(teamId)}`,
  )
  return response.json()
}

export async function unarchiveChatApi(chatId: string): Promise<void> {
  const response = await fetchWithAuth(`/api/v1/chats/${encodeURIComponent(chatId)}/archive`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || 'Failed to unarchive chat')
  }
}

export async function updateChatPermissionsApi(
  chatId: string,
  allowAllPermissions: boolean,
): Promise<void> {
  const response = await fetchWithAuth(`/api/v1/chats/${encodeURIComponent(chatId)}/permissions`, {
    body: JSON.stringify({ allowAllPermissions }),
    method: 'PATCH',
  })
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || 'Failed to update chat permissions')
  }
}
