import { fetchWithAuth } from '@/lib/auth-api'

export interface ChatDto {
  archivedAt?: null | string
  createdAt: string
  createdByDisplayName: string
  id: string
  teamId: string
  title: string
  updatedAt: string
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

export async function unarchiveChatApi(chatId: string): Promise<void> {
  const response = await fetchWithAuth(`/api/v1/chats/${encodeURIComponent(chatId)}/archive`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(errorText || 'Failed to unarchive chat')
  }
}
