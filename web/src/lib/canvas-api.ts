import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function deleteCanvasDocument(chatId: string, documentId: string): Promise<void> {
  const { accessToken } = useAuthStore.getState()
  const headers: Record<string, string> = {}
  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`
  }
  const response = await window.fetch(
    `${API_BASE_URL}/api/v1/chats/${encodeURIComponent(chatId)}/canvas/documents/${encodeURIComponent(documentId)}`,
    {
      headers,
      method: 'DELETE',
    },
  )
  if (!response.ok) {
    const errorData = (await response.json().catch(() => null)) as
      | null
      | undefined
      | { message?: string }
    throw new ApiError(errorData?.message || `HTTP ${response.status}`, response.status, errorData)
  }
}
